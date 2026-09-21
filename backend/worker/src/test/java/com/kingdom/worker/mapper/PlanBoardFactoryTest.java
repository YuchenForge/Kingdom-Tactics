package com.kingdom.worker.mapper;

import com.kingdom.api.entity.RoundPlan;
import com.kingdom.engine.domain.Board;
import com.kingdom.engine.domain.UnitInstance;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlanBoardFactoryTest {

    @Test
    void sparseJsonPlacesUnitsAtLocalCoordsWithLevels() {
        RoundPlan plan = planWithBoard(Map.of(
                "0,0", unit("u1", "Squire", 1),
                "2,3", unit("u2", "Mage", 3),
                "1,1", unit("u3", "Knight", 2)));

        Board board = PlanBoardFactory.fromPlan(plan, 0);

        assertThat(board.getPlayerId()).isEqualTo(0);
        assertThat(board.getUnitCount()).isEqualTo(3);

        UnitInstance squire = board.getUnit("u1");
        assertThat(squire.getType()).isEqualTo("Squire");
        assertThat(squire.getLevel()).isEqualTo(1);
        assertThat(squire.getX()).isEqualTo(0);
        assertThat(squire.getY()).isEqualTo(0);

        UnitInstance mage = board.getUnit("u2");
        assertThat(mage.getType()).isEqualTo("Mage");
        assertThat(mage.getLevel()).isEqualTo(3);
        assertThat(mage.getX()).isEqualTo(2);
        assertThat(mage.getY()).isEqualTo(3);

        UnitInstance knight = board.getUnit("u3");
        assertThat(knight.getType()).isEqualTo("Knight");
        assertThat(knight.getLevel()).isEqualTo(2);
        assertThat(knight.getX()).isEqualTo(1);
        assertThat(knight.getY()).isEqualTo(1);
    }

    @Test
    void laneUnitsAreIgnored() {
        RoundPlan plan = planWithBoard(Map.of("0,0", unit("board-1", "Ranger", 1)));
        plan.setLaneUnits(Arrays.asList(
                unit("lane-1", "Squire", 2),
                unit("lane-2", "Mage", 3),
                null,
                null,
                null));

        Board board = PlanBoardFactory.fromPlan(plan, 1);

        assertThat(board.getPlayerId()).isEqualTo(1);
        assertThat(board.getUnitCount()).isEqualTo(1);
        assertThat(board.getUnit("board-1")).isNotNull();
        assertThat(board.getUnit("lane-1")).isNull();
        assertThat(board.getUnit("lane-2")).isNull();
    }

    @Test
    void emptyBoardStateYieldsEmptyBoard() {
        RoundPlan plan = planWithBoard(Map.of());

        Board board = PlanBoardFactory.fromPlan(plan, 0);

        assertThat(board.getPlayerId()).isEqualTo(0);
        assertThat(board.isEmpty()).isTrue();
        assertThat(board.getUnitCount()).isZero();
    }

    @Test
    void seatSetsBoardPlayerId() {
        RoundPlan plan = planWithBoard(Map.of("1,1", unit("u", "Squire", 1)));

        assertThat(PlanBoardFactory.fromPlan(plan, 0).getPlayerId()).isEqualTo(0);
        assertThat(PlanBoardFactory.fromPlan(plan, 1).getPlayerId()).isEqualTo(1);
    }

    @Test
    void invalidUnitTypeFailsBeforePersist() {
        RoundPlan plan = planWithBoard(Map.of("1,1", unit("bad", "Dragon", 1)));

        assertThatThrownBy(() -> PlanBoardFactory.fromPlan(plan, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown unit type");
    }

    @Test
    void outOfBoundsCoordinateFailsBeforePersist() {
        RoundPlan plan = planWithBoard(Map.of("4,0", unit("oob", "Squire", 1)));

        assertThatThrownBy(() -> PlanBoardFactory.fromPlan(plan, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("out of bounds");
    }

    @Test
    void invalidSeatRejected() {
        RoundPlan plan = planWithBoard(Map.of());

        assertThatThrownBy(() -> PlanBoardFactory.fromPlan(plan, 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("seat");
    }

    private static RoundPlan planWithBoard(Map<String, Object> boardState) {
        RoundPlan plan = new RoundPlan(UUID.randomUUID(), UUID.randomUUID(), 10);
        plan.setBoardState(new HashMap<>(boardState));
        plan.setLaneUnits(new ArrayList<>(Arrays.asList(null, null, null, null, null)));
        return plan;
    }

    private static Map<String, Object> unit(String id, String type, int level) {
        Map<String, Object> map = new HashMap<>(3);
        map.put("id", id);
        map.put("type", type);
        map.put("level", level);
        return map;
    }
}
