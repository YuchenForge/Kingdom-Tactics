package com.kingdom.worker.service;

import com.kingdom.api.entity.RoundPlan;
import com.kingdom.engine.domain.Board;
import com.kingdom.engine.domain.CombatEvent;
import com.kingdom.engine.domain.ResolutionResult;
import com.kingdom.worker.mapper.PlanBoardFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ResolutionService + real CombatEngine / PlanBoardFactory (loader mocked; no DB).
 */
@ExtendWith(MockitoExtension.class)
class ResolutionServiceTest {

    private static final long FIXED_SEED = 12345L;

    @Mock
    private CombatBoardLoader combatBoardLoader;

    private ResolutionService resolutionService;

    private final UUID roundId = UUID.randomUUID();
    private final UUID gameId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        resolutionService = new ResolutionService(combatBoardLoader);
    }

    @Test
    void resolve_twoSimplePlans_stableEndReasonAndEventCount() {
        stubBoardsFromPlans(
                plan(Map.of("2,0", unit("unit_001", "Squire", 1))),
                plan(Map.of("1,2", unit("unit_002", "Squire", 1))));

        ResolutionResult result = resolutionService.resolve(roundId, gameId, FIXED_SEED);

        assertThat(result.getEndReason()).isEqualTo("DRAW");
        assertThat(result.getWinnerPlayerId()).isEqualTo(-1);
        assertThat(result.getEvents()).hasSize(15);
        assertThat(result.getEvents().get(0).getType()).isEqualTo(CombatEvent.EventType.UNIT_PLACED);
        assertThat(result.getEvents().get(0).getData())
                .containsKeys("level", "currentHp", "maxHp");
    }

    @Test
    void resolve_emptyVsEmpty_isMutualWipeDraw() {
        stubBoardsFromPlans(plan(Map.of()), plan(Map.of()));

        ResolutionResult result = resolutionService.resolve(roundId, gameId, FIXED_SEED);

        assertThat(result.getEndReason()).isEqualTo("DRAW");
        assertThat(result.getWinnerPlayerId()).isEqualTo(-1);
        assertThat(result.getFinalTick()).isZero();
        assertThat(result.getEvents()).hasSize(1);
        assertThat(result.getEvents().get(0).getType()).isEqualTo(CombatEvent.EventType.COMBAT_ENDED);
        assertThat(result.getEvents().get(0).getData()).containsEntry("reason", "DRAW");
        assertThat(result.getKeepDamageForPlayer(0)).isEqualTo(1);
        assertThat(result.getKeepDamageForPlayer(1)).isEqualTo(1);
    }

    @Test
    void resolve_sameSeedAndPlansTwice_identicalEvents() {
        RoundPlan plan0 = plan(Map.of(
                "2,0", unit("unit_001", "Squire", 1),
                "0,1", unit("unit_003", "Ranger", 1)));
        RoundPlan plan1 = plan(Map.of(
                "1,2", unit("unit_002", "Knight", 1)));
        stubBoardsFromPlans(plan0, plan1);

        ResolutionResult first = resolutionService.resolve(roundId, gameId, FIXED_SEED);
        ResolutionResult second = resolutionService.resolve(roundId, gameId, FIXED_SEED);

        assertThat(first.getEvents()).isEqualTo(second.getEvents());
        assertThat(first.getEndReason()).isEqualTo(second.getEndReason());
        assertThat(first.getFinalTick()).isEqualTo(second.getFinalTick());
        assertThat(first.getWinnerPlayerId()).isEqualTo(second.getWinnerPlayerId());
    }

    @Test
    void resolve_propagatesLoaderFailureWithoutEnginePersist() {
        when(combatBoardLoader.load(gameId, roundId))
                .thenThrow(new IllegalStateException("Missing round_plan"));

        assertThatThrownBy(() -> resolutionService.resolve(roundId, gameId, 1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Missing round_plan");
    }

    @Test
    void resolve_loadsViaLoaderThenRunsEngine() {
        stubBoardsFromPlans(
                plan(Map.of("0,0", unit("u0", "Squire", 1))),
                plan(Map.of("0,0", unit("u1", "Squire", 1))));

        ResolutionResult result = resolutionService.resolve(roundId, gameId, 42L);

        verify(combatBoardLoader).load(gameId, roundId);
        assertThat(result).isNotNull();
        assertThat(result.getEvents()).isNotEmpty();
        assertThat(result.getEndReason()).isNotBlank();
    }

    private void stubBoardsFromPlans(RoundPlan plan0, RoundPlan plan1) {
        Board board0 = PlanBoardFactory.fromPlan(plan0, 0);
        Board board1 = PlanBoardFactory.fromPlan(plan1, 1);
        when(combatBoardLoader.load(gameId, roundId))
                .thenReturn(new LoadedCombatBoards(board0, board1));
    }

    private static RoundPlan plan(Map<String, Object> boardState) {
        RoundPlan plan = new RoundPlan(UUID.randomUUID(), UUID.randomUUID(), 10);
        plan.setBoardState(new HashMap<>(boardState));
        plan.setLocked(true);
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
