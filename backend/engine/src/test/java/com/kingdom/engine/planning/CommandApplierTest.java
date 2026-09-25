package com.kingdom.engine.planning;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;


class CommandApplierTest {

    private AtomicInteger idSeq;
    private CommandApplier applier;

    @BeforeEach
    void setUp() {
        idSeq = new AtomicInteger(1);
        applier = new CommandApplier(() -> "unit_" + idSeq.getAndIncrement());
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

    private static PlanningState fullLane(PlanningState state) {
        return withLaneUnits(
            state,
            PlanningUnit.fromShop("u0", "Squire"),
            PlanningUnit.fromShop("u1", "Squire"),
            PlanningUnit.fromShop("u2", "Knight"),
            PlanningUnit.fromShop("u3", "Mage"),
            PlanningUnit.fromShop("u4", "Healer"));
    }

    private static void assertUnchanged(PlanningState original, PlanningState snapshot) {
        assertThat(original).isEqualTo(snapshot);
        assertThat(original.getGold()).isEqualTo(snapshot.getGold());
        assertThat(original.getShop()).isEqualTo(snapshot.getShop());
    }

    @Nested
    class Buy {
        @Test
        void buy_withGold_deductsGold_fillsLaneWithL1_consumesShopSlot() {
            PlanningResult result = applier.apply(baseState(), new PlanningCommand.Buy(0));

            assertThat(result.isOk()).isTrue();
            PlanningState next = result.getState();
            assertThat(next.getGold()).isEqualTo(9); // Squire costs 1
            assertThat(next.getShop().get(0)).isNull();
            assertThat(next.getShop().get(1)).isEqualTo("Mage");
            assertThat(next.getLane()[0]).isEqualTo(PlanningUnit.fromShop("unit_1", "Squire"));
            assertThat(next.getLane()[0].getLevel()).isEqualTo(1);
            assertThat(next.getLane()[1]).isNull();
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.kingdom.engine.planning.CommandApplierTest#rejectedBuys")
        void rejectedBuy_failsAndLeavesStateUnchanged(
                String label,
                PlanningState state,
                PlanningError expected) {
            PlanningState before = state;

            PlanningResult result = applier.apply(state, new PlanningCommand.Buy(0));

            assertThat(result.isFail()).isTrue();
            assertThat(result.getError()).isEqualTo(expected);
            assertUnchanged(state, before);
        }

        @Test
        void buy_threeMatching_autoMergesToLevel2() {
            PlanningState state = withLaneUnits(
                baseState(),
                PlanningUnit.fromShop("a", "Squire"),
                PlanningUnit.fromShop("b", "Squire"));

            PlanningResult result = applier.apply(state, new PlanningCommand.Buy(0)); // third Squire

            assertThat(result.isOk()).isTrue();
            PlanningState next = result.getState();
            long squireL1 = java.util.Arrays.stream(next.getLane())
                .filter(u -> u != null && u.getType().equals("Squire") && u.getLevel() == 1)
                .count();
            long squireL2 = java.util.Arrays.stream(next.getLane())
                .filter(u -> u != null && u.getType().equals("Squire") && u.getLevel() == 2)
                .count();
            assertThat(squireL1).isZero();
            assertThat(squireL2).isEqualTo(1);
            assertThat(next.getUnit("a").getLevel()).isEqualTo(2);
            assertThat(next.getLane()[0].getId()).isEqualTo("a");
            assertThat(next.containsUnit("b")).isFalse();
            assertThat(next.containsUnit("unit_1")).isFalse();
        }
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> rejectedBuys() {
        return java.util.stream.Stream.of(
                org.junit.jupiter.params.provider.Arguments.of(
                        "broke",
                        baseState().withGold(0),
                        PlanningError.INSUFFICIENT_GOLD),
                org.junit.jupiter.params.provider.Arguments.of(
                        "laneFull",
                        fullLane(baseState()),
                        PlanningError.LANE_FULL),
                org.junit.jupiter.params.provider.Arguments.of(
                        "emptyShopSlot",
                        baseState().withShop(baseState().getShop().withSlotSold(0)),
                        PlanningError.EMPTY_SHOP_SLOT));
    }

    @Nested
    class Sell {
        @Test
        void sellFromLane_refundsBaseCostTimesLevel() {
            PlanningState state = withLaneUnits(
                baseState(), new PlanningUnit("u1", "Mage", 2)); // cost 3 * 2 = 6

            PlanningResult result = applier.apply(state, new PlanningCommand.Sell("u1"));

            assertThat(result.isOk()).isTrue();
            PlanningState next = result.getState();
            assertThat(next.getGold()).isEqualTo(16);
            assertThat(next.containsUnit("u1")).isFalse();
            assertThat(next.getLane()[0]).isNull();
        }

        @Test
        void sellFromBoard_removesUnit() {
            PlanningState state = withBoardUnit(
                baseState(), PlanningUnit.fromShop("u1", "Squire"), 1, 2);

            PlanningResult result = applier.apply(state, new PlanningCommand.Sell("u1"));

            assertThat(result.isOk()).isTrue();
            assertThat(result.getState().getBoardUnit(1, 2)).isNull();
            assertThat(result.getState().getGold()).isEqualTo(11); // +1
        }
    }

    @Nested
    class Refresh {
        @Test
        void refresh_costsOne_replacesOffers() {
            PlanningResult result = applier.apply(
                baseState(),
                new PlanningCommand.Refresh(List.of("Knight", "Healer", "Shieldbearer")));

            assertThat(result.isOk()).isTrue();
            PlanningState next = result.getState();
            assertThat(next.getGold()).isEqualTo(9);
            assertThat(next.getShop().getSlots())
                .containsExactly("Knight", "Healer", "Shieldbearer");
        }
    }

    @Nested
    class Relocate {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.kingdom.engine.planning.CommandApplierTest#successfulRelocations")
        void successfulRelocation_movesUnit(
                String label,
                PlanningState state,
                PlanningCommand command,
                java.util.function.Consumer<PlanningState> assertions) {
            PlanningResult result = applier.apply(state, command);

            assertThat(result.isOk()).isTrue();
            assertions.accept(result.getState());
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.kingdom.engine.planning.CommandApplierTest#rejectedRelocations")
        void rejectedRelocation_failsAndLeavesStateUnchanged(
                String label,
                PlanningState state,
                PlanningCommand command,
                PlanningError expected,
                java.util.function.Consumer<PlanningState> extraAssertions) {
            PlanningState before = state;
            PlanningResult result = applier.apply(state, command);

            assertThat(result.isFail()).isTrue();
            assertThat(result.getError()).isEqualTo(expected);
            assertUnchanged(state, before);
            if (extraAssertions != null) {
                extraAssertions.accept(state);
            }
        }
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> rejectedRelocations() {
        PlanningState onLane = withLaneUnits(baseState(), PlanningUnit.fromShop("u1", "Squire"));
        PlanningState occupied = withBoardUnit(
                withLaneUnits(baseState(), PlanningUnit.fromShop("u1", "Squire")),
                PlanningUnit.fromShop("u2", "Mage"),
                0,
                0);
        PlanningState atCap = withLaneUnits(
                withBoardUnit(
                        withBoardUnit(
                                withBoardUnit(baseState(),
                                        PlanningUnit.fromShop("b0", "Squire"), 0, 0),
                                PlanningUnit.fromShop("b1", "Squire"), 1, 0),
                        PlanningUnit.fromShop("b2", "Squire"), 2, 0),
                PlanningUnit.fromShop("u1", "Mage"));
        return java.util.stream.Stream.of(
                org.junit.jupiter.params.provider.Arguments.of(
                        "outOfBounds",
                        onLane,
                        PlanningCommand.Relocate.toBoard("u1", 4, 0),
                        PlanningError.OUT_OF_BOUNDS,
                        (java.util.function.Consumer<PlanningState>) state ->
                                assertThat(state.isOnLane("u1")).isTrue()),
                org.junit.jupiter.params.provider.Arguments.of(
                        "occupiedCell",
                        occupied,
                        PlanningCommand.Relocate.toBoard("u1", 0, 0),
                        PlanningError.CELL_OCCUPIED,
                        null),
                org.junit.jupiter.params.provider.Arguments.of(
                        "boardCap",
                        atCap,
                        PlanningCommand.Relocate.toBoard("u1", 3, 0),
                        PlanningError.BOARD_CAP_EXCEEDED,
                        (java.util.function.Consumer<PlanningState>) state -> {
                            assertThat(state.boardUnitCount()).isEqualTo(3);
                            assertThat(state.isOnLane("u1")).isTrue();
                        }));
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> successfulRelocations() {
        return java.util.stream.Stream.of(
                org.junit.jupiter.params.provider.Arguments.of(
                        "laneToBoard",
                        withLaneUnits(baseState(), PlanningUnit.fromShop("u1", "Squire")),
                        PlanningCommand.Relocate.toBoard("u1", 2, 3),
                        (java.util.function.Consumer<PlanningState>) next -> {
                            assertThat(next.getLane()[0]).isNull();
                            assertThat(next.getBoardUnit(2, 3).getId()).isEqualTo("u1");
                            assertThat(next.isOnLane("u1")).isFalse();
                            assertThat(next.isOnBoard("u1")).isTrue();
                        }),
                org.junit.jupiter.params.provider.Arguments.of(
                        "boardToBoard",
                        withBoardUnit(baseState(), PlanningUnit.fromShop("u1", "Squire"), 0, 0),
                        PlanningCommand.Relocate.toBoard("u1", 3, 1),
                        (java.util.function.Consumer<PlanningState>) next -> {
                            assertThat(next.getBoardUnit(0, 0)).isNull();
                            assertThat(next.getBoardUnit(3, 1).getId()).isEqualTo("u1");
                        }),
                org.junit.jupiter.params.provider.Arguments.of(
                        "laneToLane",
                        withLaneUnits(baseState(), PlanningUnit.fromShop("u1", "Squire")),
                        PlanningCommand.Relocate.toLane("u1", 4),
                        (java.util.function.Consumer<PlanningState>) next -> {
                            assertThat(next.getLane()[0]).isNull();
                            assertThat(next.getLane()[4].getId()).isEqualTo("u1");
                            assertThat(next.isOnBoard("u1")).isFalse();
                        }),
                org.junit.jupiter.params.provider.Arguments.of(
                        "boardToLane",
                        withBoardUnit(baseState(), PlanningUnit.fromShop("u1", "Squire"), 2, 1),
                        PlanningCommand.Relocate.toLane("u1", 2),
                        (java.util.function.Consumer<PlanningState>) next -> {
                            assertThat(next.getBoardUnit(2, 1)).isNull();
                            assertThat(next.getLane()[2].getId()).isEqualTo("u1");
                            assertThat(next.isOnLane("u1")).isTrue();
                            assertThat(next.isOnBoard("u1")).isFalse();
                        }));
    }

    @Nested
    class Lock {
        @Test
        void lock_setsLockedTrue() {
            PlanningResult result = applier.apply(baseState(), new PlanningCommand.Lock());

            assertThat(result.isOk()).isTrue();
            assertThat(result.getState().isLocked()).isTrue();
            assertThat(result.getState().getGold()).isEqualTo(10);
        }

        @Test
        void lock_whenAlreadyLocked_fails() {
            PlanningState locked = baseState().withLocked(true);
            PlanningResult result = applier.apply(locked, new PlanningCommand.Lock());

            assertThat(result.isFail()).isTrue();
            assertThat(result.getError()).isEqualTo(PlanningError.ALREADY_LOCKED);
        }

        @Test
        void lockThenBuy_returnsLocked() {
            PlanningResult locked = applier.apply(baseState(), new PlanningCommand.Lock());
            assertThat(locked.isOk()).isTrue();

            PlanningState afterLock = locked.getState();
            PlanningState beforeBuy = afterLock;
            PlanningResult buy = applier.apply(afterLock, new PlanningCommand.Buy(0));

            assertThat(buy.isFail()).isTrue();
            assertThat(buy.getError()).isEqualTo(PlanningError.LOCKED);
            assertUnchanged(afterLock, beforeBuy);
            assertThat(afterLock.getGold()).isEqualTo(10);
            assertThat(afterLock.getShop().get(0)).isEqualTo("Squire");
        }
    }

    @Test
    void apply_doesNotMutateOriginalState() {
        PlanningState original = baseState();
        applier.apply(original, new PlanningCommand.Buy(0));

        assertThat(original.getGold()).isEqualTo(10);
        assertThat(original.getShop().get(0)).isEqualTo("Squire");
        assertThat(original.getLane()[0]).isNull();
    }
}
