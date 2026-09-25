package com.kingdom.engine.planning;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

import com.kingdom.engine.domain.UnitTypeResolver;

/**
 * Pure apply: (PlanningState, PlanningCommand) → PlanningResult.
 * Flow: validate → copy-on-write mutate → (buy → AutoMerger) → ok / fail.
 * Shop generation stays outside — refresh only replaces provided offers.
 */
public final class CommandApplier {

    private final CommandValidator validator;
    private final AutoMerger autoMerger;
    private final Supplier<String> idSupplier;

    public CommandApplier() {
        this(new CommandValidator(), new AutoMerger(), () -> UUID.randomUUID().toString());
    }

    /** Stable ids for tests: pass a deterministic Supplier */
    public CommandApplier(Supplier<String> idSupplier) {
        this(new CommandValidator(), new AutoMerger(), idSupplier);
    }

    public CommandApplier(
            CommandValidator validator, AutoMerger autoMerger, Supplier<String> idSupplier) {
        this.validator = Objects.requireNonNull(validator, "validator");
        this.autoMerger = Objects.requireNonNull(autoMerger, "autoMerger");
        this.idSupplier = Objects.requireNonNull(idSupplier, "idSupplier");
    }

    public PlanningResult apply(PlanningState state, PlanningCommand command) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(command, "command");

        var error = validator.validate(state, command);
        if (error.isPresent()) {
            return PlanningResult.fail(error.get());
        }

        PlanningState next = switch (command) {
            case PlanningCommand.Buy buy -> applyBuy(state, buy);
            case PlanningCommand.Sell sell -> applySell(state, sell);
            case PlanningCommand.Refresh refresh -> applyRefresh(state, refresh);
            case PlanningCommand.Relocate relocate -> applyRelocate(state, relocate);
            case PlanningCommand.Lock lock -> state.withLocked(true);
        };
        return PlanningResult.ok(next);
    }

    private PlanningState applyBuy(PlanningState state, PlanningCommand.Buy buy) {
        String type = state.getShop().get(buy.shopSlot());
        int cost = UnitTypeResolver.resolve(type).getCost();

        PlanningUnit unit = PlanningUnit.fromShop(idSupplier.get(), type);
        PlanningUnit[] lane = state.getLane();
        int slot = state.firstEmptyLaneSlot();
        if (slot < 0 || slot >= PlanningState.LANE_SIZE) {
            throw new IllegalStateException("Lane full during buy apply");
        }
        lane[slot] = unit;

        PlanningState next = state
            .withGold(state.getGold() - cost)
            .withShop(state.getShop().withSlotSold(buy.shopSlot()))
            .withLane(lane);

        return autoMerger.mergeAll(next);
    }

    private PlanningState applySell(PlanningState state, PlanningCommand.Sell sell) {
        PlanningHelpers.UnitLocation loc = PlanningHelpers.findUnit(state, sell.unitId())
            .orElseThrow(() -> new IllegalStateException("Unit missing during sell apply"));

        int refund = PlanningHelpers.sellRefund(loc.unit().getType(), loc.unit().getLevel());
        PlanningUnit[] lane = state.getLane();
        PlanningUnit[][] board = state.getBoard();

        switch (loc) {
            case PlanningHelpers.UnitLocation.Lane laneLoc -> lane[laneLoc.slot()] = null;
            case PlanningHelpers.UnitLocation.Board boardLoc ->
                board[boardLoc.x()][boardLoc.y()] = null;
        }

        return new PlanningState(
            state.getGold() + refund,
            state.isLocked(),
            state.getShop(),
            lane,
            board,
            state.getRoundNumber());
    }

    private PlanningState applyRefresh(PlanningState state, PlanningCommand.Refresh refresh) {
        return state
            .withGold(state.getGold() - 1)
            .withShop(state.getShop().withOffers(refresh.newOffers()));
    }

    private PlanningState applyRelocate(PlanningState state, PlanningCommand.Relocate relocate) {
        PlanningHelpers.UnitLocation loc = PlanningHelpers.findUnit(state, relocate.unitId())
            .orElseThrow(() -> new IllegalStateException("Unit missing during relocate apply"));

        PlanningUnit unit = loc.unit();
        PlanningUnit[] lane = state.getLane();
        PlanningUnit[][] board = state.getBoard();

        switch (loc) {
            case PlanningHelpers.UnitLocation.Lane fromLane -> lane[fromLane.slot()] = null;
            case PlanningHelpers.UnitLocation.Board fromBoard ->
                board[fromBoard.x()][fromBoard.y()] = null;
        }

        switch (relocate.to()) {
            case PlanningCommand.Destination.Board cell ->
                board[cell.x()][cell.y()] = unit;
            case PlanningCommand.Destination.Lane slot ->
                lane[slot.slot()] = unit;
        }

        // Apply lane + board together — chaining withLane().withBoard() would briefly
        // index the unit in both places and trip duplicate-id checks.
        return new PlanningState(
            state.getGold(),
            state.isLocked(),
            state.getShop(),
            lane,
            board,
            state.getRoundNumber());
    }
}
