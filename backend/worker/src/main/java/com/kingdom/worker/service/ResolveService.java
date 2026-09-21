package com.kingdom.worker.service;

import com.kingdom.api.entity.Game;
import com.kingdom.api.entity.GamePlayer;
import com.kingdom.api.entity.GameStates;
import com.kingdom.api.entity.Round;
import com.kingdom.api.entity.RoundPlan;
import com.kingdom.api.exception.GameNotFoundException;
import com.kingdom.api.repository.GameEventRepository;
import com.kingdom.api.repository.GamePlayerRepository;
import com.kingdom.api.repository.GameRepository;
import com.kingdom.api.repository.GameStateSnapshotRepository;
import com.kingdom.api.repository.RoundPlanRepository;
import com.kingdom.api.repository.RoundRepository;
import com.kingdom.engine.domain.CombatBoard;
import com.kingdom.engine.domain.ResolutionResult;
import com.kingdom.worker.mapper.EndSnapshotMapper;
import com.kingdom.worker.mapper.GameEventMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * TX2 — durable combat commit: events, Keep HP, end snapshots, outcome → ROUND_RESULT.
 * Does not run the engine or advance the match (advanced_at stays null).
 */
@Service
public class ResolveService {

    private static final Logger log = LoggerFactory.getLogger(ResolveService.class);

    private final RoundRepository roundRepository;
    private final GameRepository gameRepository;
    private final GameEventRepository gameEventRepository;
    private final GameStateSnapshotRepository snapshotRepository;
    private final GamePlayerRepository gamePlayerRepository;
    private final RoundPlanRepository roundPlanRepository;
    private final Clock clock;

    public ResolveService(
            RoundRepository roundRepository,
            GameRepository gameRepository,
            GameEventRepository gameEventRepository,
            GameStateSnapshotRepository snapshotRepository,
            GamePlayerRepository gamePlayerRepository,
            RoundPlanRepository roundPlanRepository,
            Clock clock) {
        this.roundRepository = roundRepository;
        this.gameRepository = gameRepository;
        this.gameEventRepository = gameEventRepository;
        this.snapshotRepository = snapshotRepository;
        this.gamePlayerRepository = gamePlayerRepository;
        this.roundPlanRepository = roundPlanRepository;
        this.clock = clock;
    }

    /**
     * Persist a successful engine result once.
     *
     * @return true if this worker committed TX2; false if already committed / not RESOLVING
     */
    @Transactional
    public boolean commit(UUID roundId, UUID gameId, int roundNumber, ResolutionResult result) {
        // Lock order: round → game → players (seat asc) — always the same to avoid deadlocks.
        Round round = roundRepository.lockResolvingRoundForCommit(roundId).orElse(null);
        if (round == null) {
            return false; // not RESOLVING / already committed
        }

        // Lock game for update
        Game game = gameRepository.lockGameForUpdate(gameId)
                .orElseThrow(() -> new GameNotFoundException(gameId));

        // Check if events already exist
        if (gameEventRepository.existsByGameIdAndRoundNumber(gameId, roundNumber)) {
            log.warn("TX2 no-op: events already exist gameId={} round={}", gameId, roundNumber);
            return false;
        }

        // Save events
        gameEventRepository.saveAll(GameEventMapper.toEntities(gameId, roundNumber, result.getEvents()));

        // Lock players for update
        List<GamePlayer> players = gamePlayerRepository.lockByGameIdOrderBySeatAsc(gameId);
        if (players.size() != 2) {
            throw new IllegalStateException(
                    "Expected 2 game_players for gameId=" + gameId + ", found " + players.size());
        }
        GamePlayer p0 = players.get(0);
        GamePlayer p1 = players.get(1);
        if (p0.getSeat() != 0 || p1.getSeat() != 1) {
            throw new IllegalStateException(
                    "Expected seats 0 and 1 for gameId=" + gameId
                            + ", found " + p0.getSeat() + " and " + p1.getSeat());
        }

        // Calculate keep damage
        int dmg0 = result.getKeepDamageForPlayer(0);
        int dmg1 = result.getKeepDamageForPlayer(1);
        int keepAfter0 = Math.max(0, p0.getKeepHp() - dmg0);
        int keepAfter1 = Math.max(0, p1.getKeepHp() - dmg1);
        p0.setKeepHp(keepAfter0);
        p1.setKeepHp(keepAfter1);

        // Save end snapshots
        CombatBoard finalBoard = result.getFinalBoard();
        int gold0 = requirePlanGold(roundId, p0.getPlayerId());
        int gold1 = requirePlanGold(roundId, p1.getPlayerId());
        snapshotRepository.save(EndSnapshotMapper.toEndSnapshot(
                gameId, roundNumber, p0.getPlayerId(), keepAfter0, gold0, finalBoard, 0));
        snapshotRepository.save(EndSnapshotMapper.toEndSnapshot(
                gameId, roundNumber, p1.getPlayerId(), keepAfter1, gold1, finalBoard, 1));

        // Set round outcome and keep damage
        Map<String, Integer> keepDamage = new HashMap<>(2);
        keepDamage.put("0", dmg0);
        keepDamage.put("1", dmg1);
        round.setOutcome(result.getEndReason());
        round.setKeepDamage(keepDamage);
        round.setFinishedAt(clock.instant());
        round.setState(GameStates.ROUND_RESULT);
        // advanced_at stays NULL — TX3 owns advancement
        game.setState(GameStates.ROUND_RESULT);

        return true;
    }

    private int requirePlanGold(UUID roundId, UUID playerId) {
        RoundPlan plan = roundPlanRepository.findByRoundIdAndPlayerId(roundId, playerId)
                .orElseThrow(() -> new IllegalStateException(
                        "Missing round_plan for gold snapshot roundId=" + roundId
                                + " playerId=" + playerId));
        return plan.getGold();
    }
}
