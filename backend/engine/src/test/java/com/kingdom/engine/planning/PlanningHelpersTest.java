package com.kingdom.engine.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;


class PlanningHelpersTest {

    private static PlanningState baseState() {
        return PlanningState.initial(10, PlanningShop.of("Squire", "Mage", "Ranger"), 1);
    }

    @Test
    void boardCap_isThreeUntilRoundFive() {
        assertThat(PlanningHelpers.boardCap(1)).isEqualTo(3);
        assertThat(PlanningHelpers.boardCap(4)).isEqualTo(3);
        assertThat(PlanningHelpers.boardCap(5)).isEqualTo(5);
        assertThat(PlanningHelpers.boardCap(9)).isEqualTo(5);
    }

    @Test
    void sellRefund_isCostTimesLevel() {
        assertThat(PlanningHelpers.sellRefund("Squire", 1)).isEqualTo(1);
        assertThat(PlanningHelpers.sellRefund("Squire", 2)).isEqualTo(2);
        assertThat(PlanningHelpers.sellRefund("Mage", 3)).isEqualTo(9);
    }

    @Test
    void findUnit_returnsLaneOrBoardLocation() {
        PlanningUnit onLane = PlanningUnit.fromShop("a", "Squire");
        PlanningUnit onBoard = PlanningUnit.fromShop("b", "Mage");

        PlanningUnit[] lane = new PlanningUnit[PlanningState.LANE_SIZE];
        lane[2] = onLane;
        PlanningUnit[][] board = baseState().getBoard();
        board[1][3] = onBoard;

        PlanningState state = baseState().withLane(lane).withBoard(board);

        assertThat(PlanningHelpers.findUnit(state, "a"))
            .contains(new PlanningHelpers.UnitLocation.Lane(2, onLane));
        assertThat(PlanningHelpers.findUnit(state, "b"))
            .contains(new PlanningHelpers.UnitLocation.Board(1, 3, onBoard));
        assertThat(PlanningHelpers.findUnit(state, "missing")).isEmpty();
    }

    @Test
    void sellRefund_unknownType_throws() {
        assertThatThrownBy(() -> PlanningHelpers.sellRefund("Dragon", 1))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
