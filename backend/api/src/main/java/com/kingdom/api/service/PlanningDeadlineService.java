package com.kingdom.api.service;

import com.kingdom.api.entity.Game;
import com.kingdom.api.entity.GameStates;
import com.kingdom.api.entity.Round;
import com.kingdom.api.entity.RoundPlan;
import com.kingdom.api.repository.GameRepository;
import com.kingdom.api.repository.RoundPlanRepository;
import com.kingdom.api.repository.RoundRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.FlushModeType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Server-clock planning deadline checks and idempotent auto-lock.
 * Does not invent board state or write synthetic LOCK_BOARD command rows.
 */
@Service
public class PlanningDeadlineService {

    private static final Logger log = LoggerFactory.getLogger(PlanningDeadlineService.class);

    private final Clock clock;
    private final RoundPlanRepository roundPlanRepository;
    private final GameRepository gameRepository;
    private final RoundRepository roundRepository;
    private final EntityManager entityManager;
    private final TransactionTemplate requiresNewTx;

    public PlanningDeadlineService(
            Clock clock,
            RoundPlanRepository roundPlanRepository,
            GameRepository gameRepository,
            RoundRepository roundRepository,
            EntityManager entityManager,
            PlatformTransactionManager transactionManager) {
        this.clock = clock;
        this.roundPlanRepository = roundPlanRepository;
        this.gameRepository = gameRepository;
        this.roundRepository = roundRepository;
        this.entityManager = entityManager;
        this.requiresNewTx = new TransactionTemplate(transactionManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * If now >= planningDeadline, auto-locks unlocked plans and may
     * transition game/round to LOCKED.
     *
     * Finalization commits in REQUIRES_NEW so a subsequent
     * DEADLINE_PASSED rejection in the caller cannot roll back the lock.
     * Never mutate the caller's managed entities: their later flush must not
     * overwrite worker progress made after this separate transaction commits.
     *
     * @return true if the deadline has passed
     */
    public boolean enforceDeadlineOrAutoLock(Game game, Round round) {
        if (clock.instant().isBefore(round.getPlanningDeadline())) {
            return false;
        }
        UUID gameId = game.getId();
        UUID roundId = round.getId();
        requiresNewTx.executeWithoutResult(status -> {
            if (!gameRepository.existsById(gameId) || !roundRepository.existsById(roundId)) {
                return;
            }
            autoLockExpiredRound(gameId, roundId);
        });
        return true;
    }

    /**
     * Scheduler / GET entry: load current round and auto-lock if the deadline has passed.
     * Idempotent no-op when not PREPARATION or still before the deadline.
     */
    @Transactional
    public void autoLockIfDeadlinePassed(UUID gameId) {
        Game game = gameRepository.findById(gameId).orElse(null);
        if (game == null) {
            return;
        }
        if (!GameStates.PREPARATION.equals(game.getState())) {
            return;
        }

        Round round = roundRepository
                .findByGameIdAndRoundNumber(gameId, game.getCurrentRound())
                .orElse(null);
        if (round == null) {
            return;
        }
        if (!GameStates.PREPARATION.equals(round.getState())) {
            return;
        }
        if (clock.instant().isBefore(round.getPlanningDeadline())) {
            return;
        }

        autoLockExpiredRound(game.getId(), round.getId());
    }

    /**
     * Locks every unlocked plan for the round (current JSONB as-is), then
     * transitions when both are locked.
     * Acquires round→game locks before any transition decision reads.
     * Idempotent if already past PREPARATION.
     */
    private void autoLockExpiredRound(UUID gameId, UUID roundId) {
        FlushModeType previous = entityManager.getFlushMode();
        entityManager.setFlushMode(FlushModeType.COMMIT);
        try {
            Round lockedRound = lockRoundForDecision(roundId);
            if (lockedRound == null) {
                return;
            }
            Game lockedGame = lockGameForDecision(lockedRound.getGameId());
            if (lockedGame == null || !lockedGame.getId().equals(gameId)) {
                return;
            }

            if (!GameStates.PREPARATION.equals(lockedGame.getState())
                    || !GameStates.PREPARATION.equals(lockedRound.getState())) {
                return;
            }

            Instant lockedAt = clock.instant();
            List<RoundPlan> plans = roundPlanRepository.findByRoundId(lockedRound.getId());
            int newlyLocked = 0;
            for (RoundPlan plan : plans) {
                if (!plan.isLocked()) {
                    plan.setLocked(true);
                    if (plan.getLockedAt() == null) {
                        plan.setLockedAt(lockedAt);
                    }
                    roundPlanRepository.save(plan);
                    newlyLocked++;
                }
            }

            boolean transitioned = transitionToLockedIfReady(lockedGame, lockedRound, plans);

            if (newlyLocked > 0) {
                log.info("Deadline auto-lock game={} round={} newlyLocked={}/{} transitioned={}",
                        lockedGame.getId(), lockedRound.getRoundNumber(), newlyLocked, plans.size(),
                        transitioned);
            } else if (transitioned) {
                log.info("Deadline reached; plans already locked game={}", lockedGame.getId());
            } else {
                log.info("Deadline reached; waiting for locked plans game={}", lockedGame.getId());
            }
        } finally {
            entityManager.setFlushMode(previous);
        }
    }

    /**
     * Manual LOCK_BOARD must take round → game locks before writing its plan,
     * matching deadline finalization. Refresh all previously loaded decision state.
     * Called inside the command transaction, before any changes are made.
     */
    public void lockForManualCommand(Game game, Round round, RoundPlan plan) {
        lockRoundForDecision(round.getId());
        lockGameForDecision(game.getId());
        entityManager.refresh(plan);
    }

    /**
     * Shared PREPARATION → LOCKED transition used by manual LOCK_BOARD and deadline auto-lock.
     *
     * Serializes concurrent lockers with round then game FOR UPDATE (same order as
     * worker claim/resolve/advance). Locks are acquired (without flushing stale managed
     * state) and entities refreshed before reading game/round/plan state.
     */
    public void maybeTransitionBothLocked(Game game, Round round) {
        FlushModeType previous = entityManager.getFlushMode();
        entityManager.setFlushMode(FlushModeType.COMMIT);
        try {
            Round lockedRound = lockRoundForDecision(round.getId());
            if (lockedRound == null) {
                return;
            }
            Game lockedGame = lockGameForDecision(lockedRound.getGameId());
            if (lockedGame == null) {
                return;
            }

            List<RoundPlan> plans = roundPlanRepository.findByRoundId(lockedRound.getId());
            if (!transitionToLockedIfReady(lockedGame, lockedRound, plans)) {
                return;
            }

            // Keep caller's managed instances in sync when they are distinct references.
            game.setState(GameStates.LOCKED);
            round.setState(GameStates.LOCKED);
        } finally {
            entityManager.setFlushMode(previous);
        }
    }

    /**
     * Round then game: acquire pessimistic locks and refresh so decision state matches
     * the locked rows (an already-managed entity is not updated by the lock query alone).
     */
    private Round lockRoundForDecision(UUID roundId) {
        Round locked = roundRepository.lockRoundForUpdate(roundId).orElse(null);
        if (locked != null) {
            entityManager.refresh(locked);
        }
        return locked;
    }

    private Game lockGameForDecision(UUID gameId) {
        Game locked = gameRepository.lockGameForUpdate(gameId).orElse(null);
        if (locked != null) {
            entityManager.refresh(locked);
        }
        return locked;
    }

    /**
     * Under held round→game locks: transition only when both seats are locked and
     * game/round are still PREPARATION (never overwrite LOCKED/RESOLVING/…).
     *
     * @return true if this call performed the PREPARATION → LOCKED write
     */
    private boolean transitionToLockedIfReady(
            Game lockedGame, Round lockedRound, List<RoundPlan> plans) {
        if (!GameStates.PREPARATION.equals(lockedGame.getState())
                || !GameStates.PREPARATION.equals(lockedRound.getState())) {
            return false;
        }
        if (plans.size() != 2 || plans.stream().anyMatch(p -> !p.isLocked())) {
            return false;
        }

        lockedGame.setState(GameStates.LOCKED);
        lockedRound.setState(GameStates.LOCKED);
        gameRepository.save(lockedGame);
        roundRepository.save(lockedRound);
        log.info("Both players locked; game={} → LOCKED", lockedGame.getId());
        return true;
    }
}
