package com.kingdom.worker.service;

import com.kingdom.api.entity.RoundPlan;
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
 * ResolutionService wiring smoke (loader mocked; real CombatEngine).
 * Detailed combat outcomes live in engine / worker integration tests.
 */
@ExtendWith(MockitoExtension.class)
class ResolutionServiceTest {

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
    void resolve_loadsViaLoaderThenRunsEngine() {
        RoundPlan plan0 = plan(Map.of("0,0", unit("u0", "Squire", 1)));
        RoundPlan plan1 = plan(Map.of("0,0", unit("u1", "Squire", 1)));
        when(combatBoardLoader.load(gameId, roundId))
                .thenReturn(new LoadedCombatBoards(
                        PlanBoardFactory.fromPlan(plan0, 0),
                        PlanBoardFactory.fromPlan(plan1, 1)));

        ResolutionResult result = resolutionService.resolve(roundId, gameId, 42L);

        verify(combatBoardLoader).load(gameId, roundId);
        assertThat(result).isNotNull();
        assertThat(result.getEvents()).isNotEmpty();
        assertThat(result.getEndReason()).isNotBlank();
    }

    @Test
    void resolve_propagatesLoaderFailure() {
        when(combatBoardLoader.load(gameId, roundId))
                .thenThrow(new IllegalStateException("Missing round_plan"));

        assertThatThrownBy(() -> resolutionService.resolve(roundId, gameId, 1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Missing round_plan");
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
