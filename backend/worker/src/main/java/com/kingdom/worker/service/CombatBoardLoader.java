package com.kingdom.worker.service;

import com.kingdom.api.entity.GamePlayer;
import com.kingdom.api.entity.RoundPlan;
import com.kingdom.api.repository.GamePlayerRepository;
import com.kingdom.api.repository.RoundPlanRepository;
import com.kingdom.worker.mapper.PlanBoardFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Seat → player_id → locked RoundPlan → engine Board.
 */
@Service
public class CombatBoardLoader {

    private final GamePlayerRepository gamePlayerRepository;
    private final RoundPlanRepository roundPlanRepository;

    public CombatBoardLoader(
            GamePlayerRepository gamePlayerRepository,
            RoundPlanRepository roundPlanRepository) {
        this.gamePlayerRepository = gamePlayerRepository;
        this.roundPlanRepository = roundPlanRepository;
    }

    /**
     * Loads both locked plans for a claimed/resolving round and builds placement boards.
     * Missing or unlocked plans are hard errors (leave round RESOLVING for ops/retry).
     */
    @Transactional(readOnly = true)
    public LoadedCombatBoards load(UUID gameId, UUID roundId) {
        List<GamePlayer> players = gamePlayerRepository.findByGameIdOrderBySeatAsc(gameId);
        if (players.size() != 2) {
            throw new IllegalStateException(
                    "Expected 2 game_players for gameId=" + gameId + ", found " + players.size());
        }

        GamePlayer seat0 = players.get(0);
        GamePlayer seat1 = players.get(1);
        if (seat0.getSeat() != 0 || seat1.getSeat() != 1) {
            throw new IllegalStateException(
                    "Expected seats 0 and 1 for gameId=" + gameId
                            + ", found " + seat0.getSeat() + " and " + seat1.getSeat());
        }

        RoundPlan plan0 = requireLockedPlan(roundId, seat0.getPlayerId(), 0);
        RoundPlan plan1 = requireLockedPlan(roundId, seat1.getPlayerId(), 1);

        return new LoadedCombatBoards(
                PlanBoardFactory.fromPlan(plan0, 0),
                PlanBoardFactory.fromPlan(plan1, 1));
    }

    // return a locked round plan or throw an exception
    private RoundPlan requireLockedPlan(UUID roundId, UUID playerId, int seat) {
        RoundPlan plan = roundPlanRepository.findByRoundIdAndPlayerId(roundId, playerId)
                .orElseThrow(() -> new IllegalStateException(
                        "Missing round_plan for roundId=" + roundId
                                + " seat=" + seat + " playerId=" + playerId));
        if (!plan.isLocked()) {
            throw new IllegalStateException(
                    "Unlocked round_plan for roundId=" + roundId
                            + " seat=" + seat + " playerId=" + playerId);
        }
        return plan;
    }
}
