package com.kingdom.api.mapper;

import com.kingdom.api.dto.CommandResponse;
import com.kingdom.api.dto.LaneSlotDto;
import com.kingdom.api.dto.UnitViewDto;
import com.kingdom.api.entity.RoundPlan;
import com.kingdom.engine.domain.Board;
import com.kingdom.engine.domain.UnitTypeResolver;
import com.kingdom.engine.planning.PlanningShop;
import com.kingdom.engine.planning.PlanningState;
import com.kingdom.engine.planning.PlanningUnit;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlanningStateMapperTest {

    @Test
    void emptyJoinPlanLoadsAsEmptyState() {
        RoundPlan plan = emptyJoinPlan(10);
        PlanningShop shop = PlanningShop.of("Squire", "Mage", "Ranger");

        PlanningState state = PlanningStateMapper.toPlanningState(plan, shop, 1);

        assertThat(state.getGold()).isEqualTo(10);
        assertThat(state.isLocked()).isFalse();
        assertThat(state.getShop()).isEqualTo(shop);
        assertThat(state.getLane()).containsOnlyNulls();
        assertThat(state.boardUnitCount()).isZero();
        assertThat(state.getRoundNumber()).isEqualTo(1);
    }

    @Test
    void lockedPlanMapsLockedFlagIntoPlanningState() {
        RoundPlan plan = emptyJoinPlan(10);
        plan.setLocked(true);

        PlanningState state = PlanningStateMapper.toPlanningState(plan, PlanningShop.empty(), 1);

        assertThat(state.isLocked()).isTrue();
    }

    @Test
    void emptyJoinPlanRoundTripsCleanly() {
        RoundPlan plan = emptyJoinPlan(10);
        PlanningShop shop = PlanningShop.empty();

        PlanningState state = PlanningStateMapper.toPlanningState(plan, shop, 1);
        PlanningStateMapper.applyToPlan(plan, state);

        assertThat(plan.getGold()).isEqualTo(10);
        assertThat(plan.isLocked()).isFalse();
        assertThat(plan.getBoardState()).isEmpty();
        assertThat(plan.getLaneUnits()).hasSize(PlanningState.LANE_SIZE);
        assertThat(plan.getLaneUnits()).containsOnlyNulls();

        PlanningState reloaded = PlanningStateMapper.toPlanningState(plan, shop, 1);
        assertThat(reloaded.getLane()).containsOnlyNulls();
        assertThat(reloaded.boardUnitCount()).isZero();
    }

    @Test
    void laneAndBoardUnitsRoundTripWithTypeAndLevel() {
        RoundPlan plan = emptyJoinPlan(7);
        PlanningShop shop = PlanningShop.of("Squire", "Mage", "Ranger").withSlotSold(1);

        PlanningUnit laneUnit = new PlanningUnit("lane-1", "Squire", 2);
        PlanningUnit boardUnit = new PlanningUnit("board-1", "Mage", 3);
        PlanningUnit[] lane = new PlanningUnit[PlanningState.LANE_SIZE];
        lane[2] = laneUnit;
        PlanningUnit[][] board = new PlanningUnit[Board.WIDTH][Board.HEIGHT];
        board[1][3] = boardUnit;

        PlanningState state = new PlanningState(7, false, shop, lane, board, 2);
        PlanningStateMapper.applyToPlan(plan, state);

        assertThat(plan.getLaneUnits().get(2))
                .isEqualTo(Map.of("id", "lane-1", "type", "Squire", "level", 2));
        assertThat(plan.getBoardState().get("1,3"))
                .isEqualTo(Map.of("id", "board-1", "type", "Mage", "level", 3));

        PlanningState reloaded = PlanningStateMapper.toPlanningState(plan, shop, 2);
        assertThat(reloaded.getGold()).isEqualTo(7);
        assertThat(reloaded.getLane()[2]).isEqualTo(laneUnit);
        assertThat(reloaded.getBoard()[1][3]).isEqualTo(boardUnit);
        assertThat(reloaded.getShop().get(1)).isNull();
    }

    @Test
    void toSnapshotResponseMapsLaneBoardUnitsAndShopStats() {
        PlanningShop shop = PlanningShop.of("Squire", "Mage", "Ranger").withSlotSold(1);
        PlanningUnit[] lane = new PlanningUnit[PlanningState.LANE_SIZE];
        lane[0] = new PlanningUnit("u1", "Knight", 1);
        PlanningUnit[][] board = new PlanningUnit[Board.WIDTH][Board.HEIGHT];
        board[0][2] = new PlanningUnit("u2", "Mage", 1);
        board[1][0] = new PlanningUnit("u3", "Squire", 2);

        PlanningState state = new PlanningState(8, true, shop, lane, board, 1);
        CommandResponse response = PlanningStateMapper.toSnapshotResponse(state);

        assertThat(response.success()).isTrue();
        assertThat(response.gold()).isEqualTo(8);
        assertThat(response.isLocked()).isTrue();
        assertThat(response.lane().get(0))
                .isEqualTo(new LaneSlotDto(0, "u1", "Knight", 1));
        assertThat(response.lane().get(1).unitId()).isNull();
        assertThat(response.board().get(2).get(0)).isEqualTo("u2");
        assertThat(response.board().get(0).get(1)).isEqualTo("u3");

        var squire = UnitTypeResolver.resolve("Squire");
        var mage = UnitTypeResolver.resolve("Mage");
        var ranger = UnitTypeResolver.resolve("Ranger");
        var knight = UnitTypeResolver.resolve("Knight");
        assertThat(response.shop()).containsExactly(
                PlanningStateMapper.toShopDto(0, "Squire"),
                PlanningStateMapper.toShopDto(1, null),
                PlanningStateMapper.toShopDto(2, "Ranger"));
        assertThat(response.shop().get(0).maxHp()).isEqualTo(squire.getMaxHp(1));
        assertThat(response.shop().get(0).attack()).isEqualTo(squire.getAttack(1));
        assertThat(response.shop().get(1).unitType()).isNull();
        assertThat(response.shop().get(2).range()).isEqualTo(ranger.getRange());

        assertThat(response.units()).containsOnlyKeys("u2", "u3", "u1");
        assertThat(response.units().get("u3")).isEqualTo(new UnitViewDto(
                "u3",
                "Squire",
                2,
                squire.getMaxHp(2),
                squire.getAttack(2),
                squire.getRange(),
                squire.getSpecialAbility(),
                squire.getHealAmount(2)));
        assertThat(response.units().get("u1").maxHp()).isEqualTo(knight.getMaxHp(1));
        assertThat(response.units().get("u2").maxHp()).isEqualTo(mage.getMaxHp(1));
        assertThat(response.opponentIsLocked()).isNull();
    }

    @Test
    void parseBoardRejectsOutOfBoundsCoordinate() {
        Map<String, Object> boardState = new HashMap<>();
        boardState.put("4,0", Map.of("id", "u", "type", "Squire", "level", 1));

        assertThatThrownBy(() -> PlanningStateMapper.parseBoard(boardState))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("out of bounds");
    }

    @Test
    void parseLaneRejectsWrongSize() {
        List<Object> shortLane = new ArrayList<>();
        shortLane.add(Map.of("id", "u", "type", "Squire", "level", 1));

        assertThatThrownBy(() -> PlanningStateMapper.parseLane(shortLane))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly " + PlanningState.LANE_SIZE);

        assertThatThrownBy(() -> PlanningStateMapper.parseLane(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly " + PlanningState.LANE_SIZE);
    }

    @Test
    void parseBoardRejectsMalformedCoordinateKey() {
        Map<String, Object> boardState = new HashMap<>();
        boardState.put("1-0", Map.of("id", "u", "type", "Squire", "level", 1));

        assertThatThrownBy(() -> PlanningStateMapper.parseBoard(boardState))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("x,y");
    }

    private static RoundPlan emptyJoinPlan(int gold) {
        RoundPlan plan = new RoundPlan(UUID.randomUUID(), UUID.randomUUID(), gold);
        plan.setBoardState(new HashMap<>());
        plan.setLaneUnits(new ArrayList<>(Arrays.asList(null, null, null, null, null)));
        return plan;
    }
}
