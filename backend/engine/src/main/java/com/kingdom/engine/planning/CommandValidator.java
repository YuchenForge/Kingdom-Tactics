package com.kingdom.engine.planning;

import java.util.Objects;
import java.util.Optional;

import com.kingdom.engine.domain.Board;
import com.kingdom.engine.domain.UnitDefinition;
import com.kingdom.engine.domain.UnitTypeResolver;

/**
 * Pure validation: (PlanningState, PlanningCommand) → Optional error.
 * Applier may assume checks passed or re-check defensively.
 */
public final class CommandValidator {

    /**
     * @return empty if the command is legal; otherwise the failure reason
     */
    public Optional<PlanningError> validate(PlanningState state, PlanningCommand command) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(command, "command");

        return switch (command) {
            case PlanningCommand.Lock lock -> validateLock(state);
            case PlanningCommand.Buy buy -> commonLocked(state).or(() -> validateBuy(state, buy));
            case PlanningCommand.Sell sell -> commonLocked(state).or(() -> validateSell(state, sell));
            case PlanningCommand.Refresh refresh ->
                commonLocked(state).or(() -> validateRefresh(state, refresh));
            case PlanningCommand.Relocate relocate ->
                commonLocked(state).or(() -> validateRelocate(state, relocate));
        };
    }

    private static Optional<PlanningError> commonLocked(PlanningState state) {
        if (state.isLocked()) {
            return Optional.of(PlanningError.LOCKED);
        }
        return Optional.empty();
    }

    private static Optional<PlanningError> validateLock(PlanningState state) {
        if (state.isLocked()) {
            return Optional.of(PlanningError.ALREADY_LOCKED);
        }
        return Optional.empty();
    }

    private static Optional<PlanningError> validateBuy(PlanningState state, PlanningCommand.Buy buy) {
        String type = state.getShop().get(buy.shopSlot());
        if (type == null) {
            return Optional.of(PlanningError.EMPTY_SHOP_SLOT);
        }
        UnitDefinition definition;
        try {
            definition = UnitTypeResolver.resolve(type);
        } catch (IllegalArgumentException ex) {
            return Optional.of(PlanningError.INVALID_UNIT_TYPE);
        }
        if (state.getGold() < definition.getCost()) {
            return Optional.of(PlanningError.INSUFFICIENT_GOLD);
        }
        if (state.isLaneFull()) {
            return Optional.of(PlanningError.LANE_FULL);
        }
        return Optional.empty();
    }

    private static Optional<PlanningError> validateSell(
            PlanningState state, PlanningCommand.Sell sell) {
        if (!state.containsUnit(sell.unitId())) {
            return Optional.of(PlanningError.UNIT_NOT_FOUND);
        }
        return Optional.empty();
    }

    private static Optional<PlanningError> validateRefresh(
            PlanningState state, PlanningCommand.Refresh refresh) {
        if (state.getGold() < 1) {
            return Optional.of(PlanningError.INSUFFICIENT_GOLD);
        }
        // Size/null already enforced by PlanningCommand.Refresh constructor.
        for (String type : refresh.newOffers()) {
            if (!UnitTypeResolver.isKnownType(type)) {
                return Optional.of(PlanningError.INVALID_REFRESH_OFFERS);
            }
        }
        return Optional.empty();
    }

    private static Optional<PlanningError> validateRelocate(
            PlanningState state, PlanningCommand.Relocate relocate) {
        if (!state.containsUnit(relocate.unitId())) {
            return Optional.of(PlanningError.UNIT_NOT_FOUND);
        }
        return switch (relocate.to()) {
            case PlanningCommand.Destination.Board board ->
                validateToBoard(state, relocate.unitId(), board);
            case PlanningCommand.Destination.Lane lane ->
                validateToLane(state, relocate.unitId(), lane);
        };
    }

    private static Optional<PlanningError> validateToBoard(
            PlanningState state, String unitId, PlanningCommand.Destination.Board board) {
        if (!Board.isValidPosition(board.x(), board.y())) {
            return Optional.of(PlanningError.OUT_OF_BOUNDS);
        }
        PlanningUnit occupant = state.getBoardUnit(board.x(), board.y());
        if (occupant != null && !occupant.getId().equals(unitId)) {
            return Optional.of(PlanningError.CELL_OCCUPIED);
        }
        if (state.isOnLane(unitId) && state.boardUnitCount() + 1 > state.boardUnitCap()) {
            return Optional.of(PlanningError.BOARD_CAP_EXCEEDED);
        }
        return Optional.empty();
    }

    private static Optional<PlanningError> validateToLane(
            PlanningState state, String unitId, PlanningCommand.Destination.Lane lane) {
        // slot range already enforced by Destination.Lane
        PlanningUnit occupant = state.getLane()[lane.slot()];
        if (occupant != null && !occupant.getId().equals(unitId)) {
            return Optional.of(PlanningError.SLOT_OCCUPIED);
        }
        return Optional.empty();
    }
}
