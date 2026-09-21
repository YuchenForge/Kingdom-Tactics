package com.kingdom.worker.service;

import com.kingdom.api.entity.GamePlayer;
import com.kingdom.api.entity.RoundPlan;
import com.kingdom.api.repository.GamePlayerRepository;
import com.kingdom.api.repository.RoundPlanRepository;
import com.kingdom.engine.domain.Board;
import com.kingdom.engine.domain.UnitInstance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CombatBoardLoaderTest {

    @Mock
    private GamePlayerRepository gamePlayerRepository;
    @Mock
    private RoundPlanRepository roundPlanRepository;

    private CombatBoardLoader loader;

    private final UUID gameId = UUID.randomUUID();
    private final UUID roundId = UUID.randomUUID();
    private final UUID player0Id = UUID.randomUUID();
    private final UUID player1Id = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        loader = new CombatBoardLoader(gamePlayerRepository, roundPlanRepository);
    }

    @Test
    void load_mapsSeatsToLockedPlansAndBoards() {
        stubTwoSeats();
        RoundPlan plan0 = lockedPlan(player0Id, Map.of(
                "0,0", unit("p0-u1", "Squire", 1)));
        RoundPlan plan1 = lockedPlan(player1Id, Map.of(
                "2,3", unit("p1-u1", "Mage", 2)));
        when(roundPlanRepository.findByRoundIdAndPlayerId(roundId, player0Id))
                .thenReturn(Optional.of(plan0));
        when(roundPlanRepository.findByRoundIdAndPlayerId(roundId, player1Id))
                .thenReturn(Optional.of(plan1));

        LoadedCombatBoards boards = loader.load(gameId, roundId);

        Board board0 = boards.board0();
        Board board1 = boards.board1();
        assertThat(board0.getPlayerId()).isEqualTo(0);
        assertThat(board1.getPlayerId()).isEqualTo(1);

        UnitInstance p0 = board0.getUnit("p0-u1");
        assertThat(p0.getType()).isEqualTo("Squire");
        assertThat(p0.getX()).isEqualTo(0);
        assertThat(p0.getY()).isEqualTo(0);

        UnitInstance p1 = board1.getUnit("p1-u1");
        assertThat(p1.getType()).isEqualTo("Mage");
        assertThat(p1.getLevel()).isEqualTo(2);
        assertThat(p1.getX()).isEqualTo(2);
        assertThat(p1.getY()).isEqualTo(3);
    }

    @Test
    void load_whenMissingPlan_throwsHardError() {
        stubTwoSeats();
        when(roundPlanRepository.findByRoundIdAndPlayerId(roundId, player0Id))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> loader.load(gameId, roundId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Missing round_plan")
                .hasMessageContaining("seat=0");
    }

    @Test
    void load_whenPlanUnlocked_throwsHardError() {
        stubTwoSeats();
        RoundPlan unlocked = lockedPlan(player0Id, Map.of());
        unlocked.setLocked(false);
        when(roundPlanRepository.findByRoundIdAndPlayerId(roundId, player0Id))
                .thenReturn(Optional.of(unlocked));

        assertThatThrownBy(() -> loader.load(gameId, roundId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unlocked round_plan")
                .hasMessageContaining("seat=0");
    }

    @Test
    void load_whenWrongPlayerCount_throws() {
        when(gamePlayerRepository.findByGameIdOrderBySeatAsc(gameId))
                .thenReturn(List.of(new GamePlayer(gameId, player0Id, 0)));

        assertThatThrownBy(() -> loader.load(gameId, roundId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Expected 2 game_players");
    }

    private void stubTwoSeats() {
        when(gamePlayerRepository.findByGameIdOrderBySeatAsc(gameId)).thenReturn(List.of(
                new GamePlayer(gameId, player0Id, 0),
                new GamePlayer(gameId, player1Id, 1)));
    }

    private RoundPlan lockedPlan(UUID playerId, Map<String, Object> boardState) {
        RoundPlan plan = new RoundPlan(roundId, playerId, 10);
        plan.setLocked(true);
        plan.setBoardState(new HashMap<>(boardState));
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
