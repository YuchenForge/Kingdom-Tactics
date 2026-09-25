package com.kingdom.engine.planning;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;


class AutoMergerTest {

    private AtomicInteger idSeq;
    private CommandApplier applier;
    private AutoMerger merger;

    @BeforeEach
    void setUp() {
        idSeq = new AtomicInteger(1);
        applier = new CommandApplier(() -> "unit_" + idSeq.getAndIncrement());
        merger = new AutoMerger();
    }

    private static PlanningState baseState() {
        return PlanningState.initial(10, PlanningShop.of("Squire", "Mage", "Ranger"), 1);
    }

    private static PlanningState withLaneUnits(PlanningState state, PlanningUnit... units) {
        PlanningUnit[] lane = new PlanningUnit[PlanningState.LANE_SIZE];
        System.arraycopy(units, 0, lane, 0, units.length);
        return state.withLane(lane);
    }

    private static PlanningState withBoardUnit(PlanningState state, PlanningUnit unit, int x, int y) {
        PlanningUnit[][] board = state.getBoard();
        board[x][y] = unit;
        return state.withBoard(board);
    }

    @Test
    void twoOnBoard_plusBuyThird_mergesOnBoardAtLowestIdCell() {
        // board: "a" at (2,1), "c" at (0,0); buy adds third Squire on lane → merge on board
        PlanningState state = baseState();
        state = withBoardUnit(state, PlanningUnit.fromShop("a", "Squire"), 2, 1);
        state = withBoardUnit(state, PlanningUnit.fromShop("c", "Squire"), 0, 0);

        PlanningResult result = applier.apply(state, new PlanningCommand.Buy(0));

        assertThat(result.isOk()).isTrue();
        PlanningState next = result.getState();
        
        assertThat(next.getBoardUnit(2, 1)).isEqualTo(new PlanningUnit("a", "Squire", 2));
        assertThat(next.getBoardUnit(0, 0)).isNull();
        assertThat(next.isOnLane("a")).isFalse();
        assertThat(next.containsUnit("c")).isFalse();
        assertThat(next.containsUnit("unit_1")).isFalse();
    }

    @Test
    void threeBuysAllLane_placesLevel2InLowestFreedLaneSlot() {
        PlanningState state = baseState();
        
        state = withLaneUnits(
            state,
            PlanningUnit.fromShop("u1", "Squire"),
            PlanningUnit.fromShop("u2", "Squire"),
            PlanningUnit.fromShop("u3", "Squire"));

        PlanningState next = merger.mergeAll(state);

        assertThat(next.getLane()[0]).isEqualTo(new PlanningUnit("u1", "Squire", 2));
        assertThat(next.getLane()[1]).isNull();
        assertThat(next.getLane()[2]).isNull();
        assertThat(next.boardUnitCount()).isZero();
    }

    @Test
    void placeDoesNotMerge_buyThatMakesThreeDoes() {
        PlanningState state = withLaneUnits(
            baseState(),
            PlanningUnit.fromShop("a", "Squire"),
            PlanningUnit.fromShop("b", "Squire"));

        // Relocate to board — still only 2 Squires, no merge
        PlanningResult placed = applier.apply(state, PlanningCommand.Relocate.toBoard("a", 1, 1));
        assertThat(placed.isOk()).isTrue();
        PlanningState afterPlace = placed.getState();
        assertThat(afterPlace.getUnit("a").getLevel()).isEqualTo(1);
        assertThat(afterPlace.getUnit("b").getLevel()).isEqualTo(1);
        assertThat(afterPlace.boardUnitCount()).isEqualTo(1);

        // Buy third Squire → merge (2 lane/board + buy)
        // Shop still has Squire in slot 0
        PlanningResult bought = applier.apply(afterPlace, new PlanningCommand.Buy(0));
        assertThat(bought.isOk()).isTrue();
        PlanningState afterBuy = bought.getState();

        assertThat(afterBuy.getUnit("a").getLevel()).isEqualTo(2);
        // "a" was on board and is lowest board id among consumed → upgrade at (1,1)
        assertThat(afterBuy.getBoardUnit(1, 1)).isEqualTo(new PlanningUnit("a", "Squire", 2));
        assertThat(afterBuy.containsUnit("b")).isFalse();
    }

    @Test
    void nineLevel1_buyNinthFromShop_cascadesToOneLevel3() {
        // 4 on lane + 4 on board = 8 L1 (lane has room for buy);
        // purchase 9th Squire from shop → cascade 9×L1 → 3×L2 → 1×L3
        PlanningState state = withLaneUnits(
            baseState(),
            PlanningUnit.fromShop("a", "Squire"),
            PlanningUnit.fromShop("b", "Squire"),
            PlanningUnit.fromShop("c", "Squire"),
            PlanningUnit.fromShop("d", "Squire"));
        state = withBoardUnit(state, PlanningUnit.fromShop("e", "Squire"), 0, 0);
        state = withBoardUnit(state, PlanningUnit.fromShop("f", "Squire"), 1, 0);
        state = withBoardUnit(state, PlanningUnit.fromShop("g", "Squire"), 2, 0);
        state = withBoardUnit(state, PlanningUnit.fromShop("h", "Squire"), 3, 0);

        PlanningResult result = applier.apply(state, new PlanningCommand.Buy(0));

        assertThat(result.isOk()).isTrue();
        PlanningState next = result.getState();

        long l1 = next.getUnitsById().values().stream()
            .filter(u -> u.getType().equals("Squire") && u.getLevel() == 1)
            .count();
        long l2 = next.getUnitsById().values().stream()
            .filter(u -> u.getType().equals("Squire") && u.getLevel() == 2)
            .count();
        long l3 = next.getUnitsById().values().stream()
            .filter(u -> u.getType().equals("Squire") && u.getLevel() == 3)
            .count();

        assertThat(l1).isZero();
        assertThat(l2).isZero();
        assertThat(l3).isEqualTo(1);
        // lowest consumed id reused through the cascade
        assertThat(next.getUnit("a").getLevel()).isEqualTo(3);
        assertThat(next.getUnitsById()).hasSize(1);
    }

    @Test
    void level3_doesNotMerge() {
        PlanningState state = withLaneUnits(
            baseState(),
            new PlanningUnit("a", "Squire", 3),
            new PlanningUnit("b", "Squire", 3),
            new PlanningUnit("c", "Squire", 3));

        PlanningState next = merger.mergeAll(state);

        assertThat(next.getUnitsById()).hasSize(3);
        assertThat(next.getUnit("a").getLevel()).isEqualTo(3);
    }
}
