package com.kingdom.engine.planning;

import java.util.Objects;
import java.util.Optional;

import com.kingdom.engine.domain.UnitTypeResolver;

/**
 * Shared planning rules helpers (catalog lookups, board cap, refunds, unit location).
 * Shop RNG stays out — callers supply / replace offers only.
 */
public final class PlanningHelpers {

    private PlanningHelpers() {
        // static utility
    }

    /** Max placement-board units: 3 before round 5, else 5. */
    public static int boardCap(int roundNumber) {
        if (roundNumber < 1) {
            throw new IllegalArgumentException("roundNumber must be >= 1: " + roundNumber);
        }
        return roundNumber >= PlanningState.LATE_BOARD_CAP_FROM_ROUND
            ? PlanningState.LATE_BOARD_CAP
            : PlanningState.EARLY_BOARD_CAP;
    }

    /** Sell refund = base cost × level */
    public static int sellRefund(String type, int level) {
        Objects.requireNonNull(type, "type");
        return UnitTypeResolver.resolve(type).getCost() * level;
    }

    /**
     * Locate a unit on lane or board.
     *
     * Return empty if unitId is not present
     */
    public static Optional<UnitLocation> findUnit(PlanningState state, String unitId) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(unitId, "unitId");

        int laneSlot = state.findLaneSlot(unitId);
        if (laneSlot >= 0) {
            return Optional.of(new UnitLocation.Lane(laneSlot, state.getLane()[laneSlot]));
        }
        int[] pos = state.findBoardPosition(unitId);
        if (pos != null) {
            return Optional.of(new UnitLocation.Board(pos[0], pos[1], state.getBoardUnit(pos[0], pos[1])));
        }
        return Optional.empty();
    }

    /** Where a planning unit currently sits. */
    public sealed interface UnitLocation {
        PlanningUnit unit();

        record Lane(int slot, PlanningUnit unit) implements UnitLocation {}

        record Board(int x, int y, PlanningUnit unit) implements UnitLocation {}
    }
}
