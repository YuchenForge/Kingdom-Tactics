package com.kingdom.engine.planning;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class CommandValidatorTest {

    private CommandValidator validator;

    @BeforeEach
    void setUp() {
        validator = new CommandValidator();
    }

    /** Unlocked round-1 state: gold 10, shop Squire/Mage/Ranger, empty lane/board. */
    private static PlanningState baseState() {
        return PlanningState.initial(10, PlanningShop.of("Squire", "Mage", "Ranger"), 1);
    }

    private static PlanningState withLaneUnits(PlanningState state, PlanningUnit... units) {
        PlanningUnit[] lane = new PlanningUnit[PlanningState.LANE_SIZE];
        for (int i = 0; i < units.length; i++) {
            lane[i] = units[i];
        }
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

    @Nested
    class CommonLock {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.kingdom.engine.planning.CommandValidatorTest#lockedCommands")
        void command_whenLocked_returnsLocked(String label, PlanningState state, PlanningCommand command) {
            assertThat(validator.validate(state, command)).contains(PlanningError.LOCKED);
        }
    }

    static Stream<Arguments> lockedCommands() {
        PlanningState lockedEmpty = baseState().withLocked(true);
        PlanningState lockedLane = withLaneUnits(baseState(), PlanningUnit.fromShop("u1", "Squire"))
                .withLocked(true);
        PlanningState lockedBoard = withBoardUnit(
                baseState(), PlanningUnit.fromShop("u1", "Squire"), 0, 0)
                .withLocked(true);
        return Stream.of(
                Arguments.of("buy", lockedEmpty, new PlanningCommand.Buy(0)),
                Arguments.of("sell", lockedLane, new PlanningCommand.Sell("u1")),
                Arguments.of("refresh", lockedEmpty,
                        new PlanningCommand.Refresh(List.of("Squire", "Knight", "Healer"))),
                Arguments.of("relocateToBoard", lockedLane, PlanningCommand.Relocate.toBoard("u1", 0, 0)),
                Arguments.of("relocateToLane", lockedBoard, PlanningCommand.Relocate.toLane("u1", 0)));
    }

    @Nested
    class Buy {
        @Test
        void validBuy_returnsEmpty() {
            assertThat(validator.validate(baseState(), new PlanningCommand.Buy(0))).isEmpty();
        }

        @Test
        void emptyShopSlot_returnsEmptyShopSlot() {
            PlanningState sold = baseState().withShop(baseState().getShop().withSlotSold(0));
            assertThat(validator.validate(sold, new PlanningCommand.Buy(0)))
                .contains(PlanningError.EMPTY_SHOP_SLOT);
        }

        @Test
        void unknownShopType_returnsInvalidUnitType() {
            PlanningState corrupt = baseState()
                .withShop(new PlanningShop(java.util.List.of("Dragon", "Mage", "Ranger")));
            assertThat(validator.validate(corrupt, new PlanningCommand.Buy(0)))
                .contains(PlanningError.INVALID_UNIT_TYPE);
        }

        @Test
        void insufficientGold_returnsInsufficientGold() {
            PlanningState poor = baseState().withGold(0);
            assertThat(validator.validate(poor, new PlanningCommand.Buy(0)))
                .contains(PlanningError.INSUFFICIENT_GOLD);
        }

        @Test
        void laneFull_returnsLaneFull() {
            PlanningState state = fullLane(baseState());
            assertThat(validator.validate(state, new PlanningCommand.Buy(0)))
                .contains(PlanningError.LANE_FULL);
        }
    }

    @Nested
    class Sell {
        @Test
        void sellUnitOnLane_returnsEmpty() {
            PlanningState state = withLaneUnits(baseState(), PlanningUnit.fromShop("u1", "Squire"));
            assertThat(validator.validate(state, new PlanningCommand.Sell("u1"))).isEmpty();
        }

        @Test
        void sellUnitOnBoard_returnsEmpty() {
            PlanningState state = withBoardUnit(
                baseState(), PlanningUnit.fromShop("u1", "Squire"), 2, 2);
            assertThat(validator.validate(state, new PlanningCommand.Sell("u1"))).isEmpty();
        }

        @Test
        void unknownUnit_returnsUnitNotFound() {
            assertThat(validator.validate(baseState(), new PlanningCommand.Sell("missing")))
                .contains(PlanningError.UNIT_NOT_FOUND);
        }
    }

    @Nested
    class Refresh {
        @Test
        void validRefresh_returnsEmpty() {
            assertThat(validator.validate(
                    baseState(),
                    new PlanningCommand.Refresh(List.of("Knight", "Healer", "Ranger"))))
                .isEmpty();
        }

        @Test
        void insufficientGold_returnsInsufficientGold() {
            PlanningState poor = baseState().withGold(0);
            assertThat(validator.validate(
                    poor, new PlanningCommand.Refresh(List.of("Squire", "Mage", "Ranger"))))
                .contains(PlanningError.INSUFFICIENT_GOLD);
        }

        @Test
        void unknownType_returnsInvalidRefreshOffers() {
            assertThat(validator.validate(
                    baseState(),
                    new PlanningCommand.Refresh(List.of("Squire", "Dragon", "Ranger"))))
                .contains(PlanningError.INVALID_REFRESH_OFFERS);
        }
    }

    @Nested
    class Relocate {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.kingdom.engine.planning.CommandValidatorTest#validRelocations")
        void validRelocation_returnsEmpty(String label, PlanningState state, PlanningCommand command) {
            assertThat(validator.validate(state, command)).isEmpty();
        }

        @Test
        void unitNotFound_returnsUnitNotFound() {
            assertThat(validator.validate(baseState(), PlanningCommand.Relocate.toBoard("missing", 0, 0)))
                .contains(PlanningError.UNIT_NOT_FOUND);
        }

        @Test
        void outOfBounds_returnsOutOfBounds() {
            PlanningState state = withLaneUnits(baseState(), PlanningUnit.fromShop("u1", "Squire"));
            assertThat(validator.validate(state, PlanningCommand.Relocate.toBoard("u1", 4, 0)))
                .contains(PlanningError.OUT_OF_BOUNDS);
        }

        @Test
        void boardCellOccupied_returnsCellOccupied() {
            PlanningUnit onBoard = PlanningUnit.fromShop("u2", "Mage");
            PlanningState state = withLaneUnits(baseState(), PlanningUnit.fromShop("u1", "Squire"));
            state = withBoardUnit(state, onBoard, 0, 0);
            assertThat(validator.validate(state, PlanningCommand.Relocate.toBoard("u1", 0, 0)))
                .contains(PlanningError.CELL_OCCUPIED);
        }

        @Test
        void laneSlotOccupied_returnsSlotOccupied() {
            PlanningState state = withLaneUnits(
                baseState(),
                PlanningUnit.fromShop("u1", "Squire"),
                PlanningUnit.fromShop("u2", "Mage"));
            assertThat(validator.validate(state, PlanningCommand.Relocate.toLane("u1", 1)))
                .contains(PlanningError.SLOT_OCCUPIED);
        }

        @Test
        void boardCapExceeded_whenEnteringFromLane() {
            PlanningState state = baseState();
            state = withBoardUnit(state, PlanningUnit.fromShop("b0", "Squire"), 0, 0);
            state = withBoardUnit(state, PlanningUnit.fromShop("b1", "Squire"), 1, 0);
            state = withBoardUnit(state, PlanningUnit.fromShop("b2", "Squire"), 2, 0);
            state = withLaneUnits(state, PlanningUnit.fromShop("u1", "Mage"));
            assertThat(validator.validate(state, PlanningCommand.Relocate.toBoard("u1", 3, 0)))
                .contains(PlanningError.BOARD_CAP_EXCEEDED);
        }

        @Test
        void boardCapNotApplied_whenAlreadyOnBoard() {
            PlanningState state = baseState();
            state = withBoardUnit(state, PlanningUnit.fromShop("b0", "Squire"), 0, 0);
            state = withBoardUnit(state, PlanningUnit.fromShop("b1", "Squire"), 1, 0);
            state = withBoardUnit(state, PlanningUnit.fromShop("b2", "Squire"), 2, 0);
            assertThat(validator.validate(state, PlanningCommand.Relocate.toBoard("b0", 3, 0)))
                .isEmpty();
        }

        @Test
        void boardCapAllowsFive_fromRoundFive() {
            PlanningState state = PlanningState.initial(
                10, PlanningShop.of("Squire", "Mage", "Ranger"), 5);
            state = withBoardUnit(state, PlanningUnit.fromShop("b0", "Squire"), 0, 0);
            state = withBoardUnit(state, PlanningUnit.fromShop("b1", "Squire"), 1, 0);
            state = withBoardUnit(state, PlanningUnit.fromShop("b2", "Squire"), 2, 0);
            state = withLaneUnits(state, PlanningUnit.fromShop("u1", "Mage"));
            assertThat(validator.validate(state, PlanningCommand.Relocate.toBoard("u1", 3, 0)))
                .isEmpty();
        }

        @Test
        void relocateToSameBoardCell_returnsEmpty() {
            PlanningState state = withBoardUnit(
                baseState(), PlanningUnit.fromShop("u1", "Squire"), 1, 1);
            assertThat(validator.validate(state, PlanningCommand.Relocate.toBoard("u1", 1, 1)))
                .isEmpty();
        }

        @Test
        void relocateToSameLaneSlot_returnsEmpty() {
            PlanningState state = withLaneUnits(baseState(), PlanningUnit.fromShop("u1", "Squire"));
            assertThat(validator.validate(state, PlanningCommand.Relocate.toLane("u1", 0)))
                .isEmpty();
        }
    }

    static Stream<Arguments> validRelocations() {
        PlanningState onLane = withLaneUnits(baseState(), PlanningUnit.fromShop("u1", "Squire"));
        PlanningState onBoard = withBoardUnit(
                baseState(), PlanningUnit.fromShop("u1", "Squire"), 0, 0);
        PlanningState onBoardMid = withBoardUnit(
                baseState(), PlanningUnit.fromShop("u1", "Squire"), 1, 1);
        return Stream.of(
                Arguments.of("laneToBoard", onLane, PlanningCommand.Relocate.toBoard("u1", 0, 0)),
                Arguments.of("boardToBoard", onBoard, PlanningCommand.Relocate.toBoard("u1", 2, 2)),
                Arguments.of("laneToLane", onLane, PlanningCommand.Relocate.toLane("u1", 3)),
                Arguments.of("boardToLane", onBoardMid, PlanningCommand.Relocate.toLane("u1", 0)));
    }

    @Nested
    class Lock {
        @Test
        void lockWhenUnlocked_returnsEmpty() {
            assertThat(validator.validate(baseState(), new PlanningCommand.Lock())).isEmpty();
        }

        @Test
        void lockWhenAlreadyLocked_returnsAlreadyLocked() {
            PlanningState locked = baseState().withLocked(true);
            assertThat(validator.validate(locked, new PlanningCommand.Lock()))
                .contains(PlanningError.ALREADY_LOCKED);
        }
    }
}
