package com.kingdom.worker.service;

import com.kingdom.api.entity.Game;
import com.kingdom.api.entity.GamePlayer;
import com.kingdom.api.entity.GameStates;
import com.kingdom.api.entity.Round;
import com.kingdom.api.entity.RoundPlan;
import com.kingdom.api.exception.GameNotFoundException;
import com.kingdom.api.repository.GameEventRepository;
import com.kingdom.api.repository.GamePlayerRepository;
import com.kingdom.api.repository.GameRepository;
import com.kingdom.api.repository.GameStateSnapshotRepository;
import com.kingdom.api.repository.RoundPlanRepository;
import com.kingdom.api.repository.RoundRepository;
import com.kingdom.engine.domain.Board;
import com.kingdom.engine.domain.CombatBoard;
import com.kingdom.engine.domain.CombatEvent;
import com.kingdom.engine.domain.ResolutionResult;
import com.kingdom.engine.domain.UnitDefinition;
import com.kingdom.engine.domain.UnitInstance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResolveServiceTest {

    private static final Instant NOW = Instant.parse("2024-06-01T12:30:00Z");

    @Mock
    private RoundRepository roundRepository;
    @Mock
    private GameRepository gameRepository;
    @Mock
    private GameEventRepository gameEventRepository;
    @Mock
    private GameStateSnapshotRepository snapshotRepository;
    @Mock
    private GamePlayerRepository gamePlayerRepository;
    @Mock
    private RoundPlanRepository roundPlanRepository;

    private ResolveService resolveService;

    private final UUID roundId = UUID.randomUUID();
    private final UUID gameId = UUID.randomUUID();
    private final UUID player0Id = UUID.randomUUID();
    private final UUID player1Id = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        resolveService = new ResolveService(
                roundRepository,
                gameRepository,
                gameEventRepository,
                snapshotRepository,
                gamePlayerRepository,
                roundPlanRepository,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void commit_whenNotResolving_returnsFalse() {
        when(roundRepository.lockResolvingRoundForCommit(roundId)).thenReturn(Optional.empty());

        boolean committed = resolveService.commit(roundId, gameId, 1, stubResult(1, 1));

        assertThat(committed).isFalse();
        verifyNoInteractions(gameRepository, gameEventRepository, snapshotRepository, gamePlayerRepository);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 8, 160})
    void commit_persistsEventsKeepHpSnapshotsAndRoundResult(int finalTick) {
        Round round = resolvingRound();
        Game game = resolvingGame();
        GamePlayer p0 = new GamePlayer(gameId, player0Id, 0);
        p0.setKeepHp(20);
        GamePlayer p1 = new GamePlayer(gameId, player1Id, 1);
        p1.setKeepHp(15);

        when(roundRepository.lockResolvingRoundForCommit(roundId)).thenReturn(Optional.of(round));
        when(gameRepository.lockGameForUpdate(gameId)).thenReturn(Optional.of(game));
        when(gamePlayerRepository.lockByGameIdOrderBySeatAsc(gameId)).thenReturn(List.of(p0, p1));
        when(roundPlanRepository.findByRoundIdAndPlayerId(roundId, player0Id))
                .thenReturn(Optional.of(planWithGold(player0Id, 11)));
        when(roundPlanRepository.findByRoundIdAndPlayerId(roundId, player1Id))
                .thenReturn(Optional.of(planWithGold(player1Id, 9)));

        ResolutionResult result = stubResult(2, 3, finalTick);
        boolean committed = resolveService.commit(roundId, gameId, 1, result);

        assertThat(committed).isTrue();
        verify(gameEventRepository).saveAll(anyList());
        verify(snapshotRepository, times(2)).save(any());

        assertThat(p0.getKeepHp()).isEqualTo(18); // max(0, 20 - 2)
        assertThat(p1.getKeepHp()).isEqualTo(12); // max(0, 15 - 3)

        assertThat(round.getState()).isEqualTo(GameStates.ROUND_RESULT);
        assertThat(game.getState()).isEqualTo(GameStates.ROUND_RESULT);
        assertThat(round.getOutcome()).isEqualTo("DRAW");
        assertThat(round.getKeepDamage()).isEqualTo(Map.of("0", 2, "1", 3));
        assertThat(round.getFinishedAt()).isEqualTo(NOW);
        assertThat(round.getPresentationStartsAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(round.getCombatEndsAt()).isEqualTo(NOW.plusMillis(1000L + finalTick * 250L));
        assertThat(round.getPresentationEndsAt()).isEqualTo(NOW.plusMillis(3000L + finalTick * 250L));
        assertThat(round.getTickDurationMs()).isEqualTo(250);
        assertThat(round.getAdvancedAt()).isNull();
        // round_plans are only read for gold — never saved/mutated in TX2
        verify(roundPlanRepository, never()).save(any());
        verify(roundPlanRepository, never()).saveAll(any());
    }

    @Test
    void commit_clampsKeepHpAtZero() {
        Round round = resolvingRound();
        Game game = resolvingGame();
        GamePlayer p0 = new GamePlayer(gameId, player0Id, 0);
        p0.setKeepHp(2);
        GamePlayer p1 = new GamePlayer(gameId, player1Id, 1);
        p1.setKeepHp(5);

        when(roundRepository.lockResolvingRoundForCommit(roundId)).thenReturn(Optional.of(round));
        when(gameRepository.lockGameForUpdate(gameId)).thenReturn(Optional.of(game));
        when(gamePlayerRepository.lockByGameIdOrderBySeatAsc(gameId)).thenReturn(List.of(p0, p1));
        when(roundPlanRepository.findByRoundIdAndPlayerId(roundId, player0Id))
                .thenReturn(Optional.of(planWithGold(player0Id, 10)));
        when(roundPlanRepository.findByRoundIdAndPlayerId(roundId, player1Id))
                .thenReturn(Optional.of(planWithGold(player1Id, 10)));

        assertThat(resolveService.commit(roundId, gameId, 1, stubResult(10, 5))).isTrue();

        assertThat(p0.getKeepHp()).isZero(); // max(0, 2 - 10)
        assertThat(p1.getKeepHp()).isZero(); // max(0, 5 - 5)
        assertThat(round.getKeepDamage()).isEqualTo(Map.of("0", 10, "1", 5));
    }

    @Test
    void commit_whenGameMissing_throws() {
        when(roundRepository.lockResolvingRoundForCommit(roundId))
                .thenReturn(Optional.of(resolvingRound()));
        when(gameRepository.lockGameForUpdate(gameId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolveService.commit(roundId, gameId, 1, stubResult(0, 0)))
                .isInstanceOf(GameNotFoundException.class);
    }

    private Round resolvingRound() {
        Round round = new Round(gameId, 1, GameStates.RESOLVING, Instant.parse("2024-06-01T12:00:45Z"));
        ReflectionTestUtils.setField(round, "id", roundId);
        round.setCombatSeed(99L);
        return round;
    }

    private Game resolvingGame() {
        Game game = Game.create(player0Id);
        ReflectionTestUtils.setField(game, "id", gameId);
        game.setState(GameStates.RESOLVING);
        return game;
    }

    private RoundPlan planWithGold(UUID playerId, int gold) {
        return new RoundPlan(roundId, playerId, gold);
    }

    private static ResolutionResult stubResult(int dmg0, int dmg1) {
        return stubResult(dmg0, dmg1, 0);
    }

    private static ResolutionResult stubResult(int dmg0, int dmg1, int finalTick) {
        CombatBoard board = CombatBoard.merge(new Board(0), new Board(1));
        UnitInstance ghost = new UnitInstance("u", UnitDefinition.squire(), 0, 0);
        ghost.setPlayerId(0);
        List<CombatEvent> events = List.of(
                CombatEvent.unitPlaced(0, ghost),
                CombatEvent.combatEnded(finalTick, "DRAW"));
        return new ResolutionResult(events, board, new int[] {dmg0, dmg1}, finalTick, "DRAW", -1);
    }
}
