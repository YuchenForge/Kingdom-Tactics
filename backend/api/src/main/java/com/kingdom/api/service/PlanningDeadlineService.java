package com.kingdom.api.service;

import com.kingdom.api.entity.Game;
import com.kingdom.api.entity.GameStates;
import com.kingdom.api.entity.Round;
import com.kingdom.api.entity.RoundPlan;
import com.kingdom.api.repository.GameRepository;
import com.kingdom.api.repository.RoundPlanRepository;
import com.kingdom.api.repository.RoundRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    public PlanningDeadlineService(
            Clock clock,
            RoundPlanRepository roundPlanRepository,
            GameRepository gameRepository,
            RoundRepository roundRepository) {
        this.clock = clock;
        this.roundPlanRepository = roundPlanRepository;
        this.gameRepository = gameRepository;
        this.roundRepository = roundRepository;
    }

    /**
     * If now >= planningDeadline, auto-locks unlocked plans and may
     * transition game/round to LOCKED
     *
     * return true if the deadline has passed
     */
    public boolean enforceDeadlineOrAutoLock(Game game, Round round) {
        if (clock.instant().isBefore(round.getPlanningDeadline())) {
            return false;
        }
        autoLockExpiredRound(game, round);
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

        autoLockExpiredRound(game, round);
    }

    /**
     * Locks every unlocked plan for the round (current JSONB as-is), then
     * transitions via maybeTransitionBothLocked when both are locked.
     * Idempotent if already past PREPARATION.
     */
    private void autoLockExpiredRound(Game game, Round round) {
        if (!GameStates.PREPARATION.equals(game.getState())
                || !GameStates.PREPARATION.equals(round.getState())) {
            return;
        }

        Instant lockedAt = clock.instant();
        List<RoundPlan> plans = roundPlanRepository.findByRoundId(round.getId());
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

        maybeTransitionBothLocked(game, round);

        if (newlyLocked > 0) {
            log.info("Deadline auto-lock game={} round={} newlyLocked={}/{}",
                    game.getId(), round.getRoundNumber(), newlyLocked, plans.size());
        } else {
            log.info("Deadline reached; plans already locked game={}", game.getId());
        }
    }

    /**
     * Shared PREPARATION → LOCKED transition used by manual LOCK_BOARD and deadline auto-lock.
     * No-op unless every plan for the round is locked.
     */
    public void maybeTransitionBothLocked(Game game, Round round) {
        List<RoundPlan> plans = roundPlanRepository.findByRoundId(round.getId());
        if (plans.isEmpty() || plans.stream().anyMatch(p -> !p.isLocked())) {
            return;
        }
        if (GameStates.LOCKED.equals(game.getState())
                && GameStates.LOCKED.equals(round.getState())) {
            return;
        }

        game.setState(GameStates.LOCKED);
        round.setState(GameStates.LOCKED);
        gameRepository.save(game);
        roundRepository.save(round);
        log.info("Both players locked; game={} → LOCKED", game.getId());
    }
}
