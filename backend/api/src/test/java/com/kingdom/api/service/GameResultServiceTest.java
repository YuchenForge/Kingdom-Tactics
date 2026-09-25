package com.kingdom.api.service;

import com.kingdom.api.dto.EventsResponse;
import com.kingdom.api.dto.MatchResultResponse;
import com.kingdom.api.dto.RoundResultResponse;
import com.kingdom.api.entity.Game;
import com.kingdom.api.entity.GameEvent;
import com.kingdom.api.entity.GamePlayer;
import com.kingdom.api.entity.GameStateSnapshot;
import com.kingdom.api.entity.GameStates;
import com.kingdom.api.entity.Round;
import com.kingdom.api.entity.User;
import com.kingdom.api.exception.RoundNotFoundException;
import com.kingdom.api.exception.WrongGameStateException;
import com.kingdom.api.repository.GameEventRepository;
import com.kingdom.api.repository.GamePlayerRepository;
import com.kingdom.api.repository.GameRepository;
import com.kingdom.api.repository.GameStateSnapshotRepository;
import com.kingdom.api.repository.RoundRepository;
import com.kingdom.api.repository.UserRepository;
import com.kingdom.engine.domain.CombatOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GameResultServiceTest {

    @Mock
    private GameService gameService;
    @Mock
    private GameRepository gameRepository;
    @Mock
    private RoundRepository roundRepository;
    @Mock
    private GameEventRepository gameEventRepository;
    @Mock
    private GameStateSnapshotRepository snapshotRepository;
    @Mock
    private GamePlayerRepository gamePlayerRepository;
    @Mock
    private UserRepository userRepository;

    private GameResultService service;

    private final UUID gameId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID player0 = UUID.randomUUID();
    private final UUID player1 = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new GameResultService(
                gameService,
                gameRepository,
                roundRepository,
                gameEventRepository,
                snapshotRepository,
                gamePlayerRepository,
                userRepository);
        doNothing().when(gameService).assertParticipant(gameId, userId);
    }

    @Test
    void getEvents_whileResolving_returnsEmptyIncompletePage() {
        Round round = new Round(gameId, 1, GameStates.RESOLVING, Instant.now());
        when(roundRepository.findByGameIdAndRoundNumber(gameId, 1)).thenReturn(Optional.of(round));
        when(gameEventRepository.findByGameIdAndRoundNumberAndSequenceNumGreaterThanOrderBySequenceNumAsc(
                eq(gameId), eq(1), eq(0), any(Pageable.class)))
                .thenReturn(List.of());

        EventsResponse response = service.getEvents(gameId, userId, 1, null, null);

        assertThat(response.roundNumber()).isEqualTo(1);
        assertThat(response.events()).isEmpty();
        assertThat(response.nextAfterSequence()).isZero();
        assertThat(response.hasMore()).isFalse();
        assertThat(response.complete()).isFalse();
        verify(gameService).assertParticipant(gameId, userId);
    }

    @Test
    void getEvents_pagesWithHasMoreAndComplete() {
        Round round = new Round(gameId, 2, GameStates.ROUND_RESULT, Instant.now());
        round.setOutcome("TIME_LIMIT");
        when(roundRepository.findByGameIdAndRoundNumber(gameId, 2)).thenReturn(Optional.of(round));

        GameEvent e1 = new GameEvent(gameId, 2, 1, "UNIT_MOVED", Map.of("x", 1), 0);
        GameEvent e2 = new GameEvent(gameId, 2, 2, "ATTACK", Map.of("dmg", 2), 4);
        GameEvent e3 = new GameEvent(gameId, 2, 3, "UNIT_DIED", Map.of(), 4);
        when(gameEventRepository.findByGameIdAndRoundNumberAndSequenceNumGreaterThanOrderBySequenceNumAsc(
                eq(gameId), eq(2), eq(0), any(Pageable.class)))
                .thenReturn(List.of(e1, e2, e3));

        EventsResponse response = service.getEvents(gameId, userId, 2, 0, 2);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(gameEventRepository)
                .findByGameIdAndRoundNumberAndSequenceNumGreaterThanOrderBySequenceNumAsc(
                        eq(gameId), eq(2), eq(0), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(3); // limit+1

        assertThat(response.events()).hasSize(2);
        assertThat(response.events().get(0).sequenceNumber()).isEqualTo(1);
        assertThat(response.events().get(0).type()).isEqualTo("UNIT_MOVED");
        assertThat(response.nextAfterSequence()).isEqualTo(2);
        assertThat(response.hasMore()).isTrue();
        assertThat(response.complete()).isTrue();
    }

    @Test
    void getEvents_capsLimitAt500() {
        Round round = new Round(gameId, 1, GameStates.RESOLVING, Instant.now());
        when(roundRepository.findByGameIdAndRoundNumber(gameId, 1)).thenReturn(Optional.of(round));
        when(gameEventRepository.findByGameIdAndRoundNumberAndSequenceNumGreaterThanOrderBySequenceNumAsc(
                eq(gameId), eq(1), eq(0), any(Pageable.class)))
                .thenReturn(List.of());

        service.getEvents(gameId, userId, 1, 0, 999);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(gameEventRepository)
                .findByGameIdAndRoundNumberAndSequenceNumGreaterThanOrderBySequenceNumAsc(
                        eq(gameId), eq(1), eq(0), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(501); // 500+1
    }

    @Test
    void getEvents_unknownRound_throwsRoundNotFound() {
        when(roundRepository.findByGameIdAndRoundNumber(gameId, 3)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getEvents(gameId, userId, 3, 0, 50))
                .isInstanceOf(RoundNotFoundException.class);
    }

    @Test
    void getEvents_invalidRound_throwsIllegalArgument() {
        assertThatThrownBy(() -> service.getEvents(gameId, userId, 0, 0, 50))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.getEvents(gameId, userId, 9, 0, 50))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getRoundResult_beforeTx2_throwsWrongGameState() {
        Round round = new Round(gameId, 1, GameStates.RESOLVING, Instant.now());
        when(roundRepository.findByGameIdAndRoundNumber(gameId, 1)).thenReturn(Optional.of(round));

        assertThatThrownBy(() -> service.getRoundResult(gameId, userId, 1))
                .isInstanceOf(WrongGameStateException.class)
                .hasMessageContaining("not yet resolved");
    }

    @Test
    void getRoundResult_usesEndSnapshotsNotLiveKeepHp() {
        Round round = new Round(gameId, 1, GameStates.ROUND_RESULT, Instant.now());
        round.setOutcome(CombatOutcome.ENEMY_VICTORY.name());
        round.setKeepDamage(Map.of("0", 3, "1", 0));
        when(roundRepository.findByGameIdAndRoundNumber(gameId, 1)).thenReturn(Optional.of(round));

        GamePlayer seat0 = new GamePlayer(gameId, player0, 0);
        seat0.setKeepHp(1); // live HP after later rounds — must NOT appear in response
        GamePlayer seat1 = new GamePlayer(gameId, player1, 1);
        seat1.setKeepHp(20);
        when(gamePlayerRepository.findByGameIdOrderBySeatAsc(gameId)).thenReturn(List.of(seat0, seat1));

        List<Object> survivors0 = List.of(Map.of("id", "u1", "type", "Squire"));
        GameStateSnapshot snap0 = new GameStateSnapshot(
                gameId, 1, false, player0, 17, 10, survivors0, List.of(), null);
        GameStateSnapshot snap1 = new GameStateSnapshot(
                gameId, 1, false, player1, 20, 10, List.of(), List.of(), null);
        when(snapshotRepository.findByGameIdAndRoundNumberAndRoundStart(gameId, 1, false))
                .thenReturn(List.of(snap0, snap1));

        RoundResultResponse response = service.getRoundResult(gameId, userId, 1);

        assertThat(response.roundNumber()).isEqualTo(1);
        assertThat(response.outcome()).isEqualTo(CombatOutcome.ENEMY_VICTORY.name());
        assertThat(response.keepDamage()).isEqualTo(Map.of("0", 3, "1", 0));
        assertThat(response.keepHpAfter()).containsEntry("0", 17).containsEntry("1", 20);
        assertThat(response.endSnapshots().get("0").survivors()).isEqualTo(survivors0);
        assertThat(response.endSnapshots().get("0").keepHp()).isEqualTo(17);
        assertThat(response.endSnapshots().get("1").keepHp()).isEqualTo(20);
    }

    @Test
    void getRoundResult_missingRound_throwsNotFound() {
        when(roundRepository.findByGameIdAndRoundNumber(gameId, 4)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getRoundResult(gameId, userId, 4))
                .isInstanceOf(RoundNotFoundException.class);
    }

    @Test
    void getMatchResult_notFinished_throwsWrongGameState() {
        Game game = Game.create(player0);
        ReflectionTestUtils.setField(game, "id", gameId);
        game.setState(GameStates.PREPARATION);
        when(gameRepository.findById(gameId)).thenReturn(Optional.of(game));

        assertThatThrownBy(() -> service.getMatchResult(gameId, userId))
                .isInstanceOf(WrongGameStateException.class)
                .hasMessageContaining("not finished");
    }

    @Test
    void getMatchResult_withWinner_resolvesUsernamesAndDuration() {
        Instant started = Instant.parse("2024-06-01T12:00:00Z");
        Instant finished = Instant.parse("2024-06-01T12:05:30Z");
        Game game = finishedGame(started, finished, player0, 3);

        when(gameRepository.findById(gameId)).thenReturn(Optional.of(game));
        GamePlayer seat0 = new GamePlayer(gameId, player0, 0);
        seat0.setKeepHp(12);
        GamePlayer seat1 = new GamePlayer(gameId, player1, 1);
        seat1.setKeepHp(0);
        when(gamePlayerRepository.findByGameIdOrderBySeatAsc(gameId)).thenReturn(List.of(seat0, seat1));

        User winner = new User("alice", "a@test.com", "h".getBytes());
        ReflectionTestUtils.setField(winner, "id", player0);
        User loser = new User("bob", "b@test.com", "h".getBytes());
        ReflectionTestUtils.setField(loser, "id", player1);
        when(userRepository.findById(player0)).thenReturn(Optional.of(winner));
        when(userRepository.findById(player1)).thenReturn(Optional.of(loser));

        MatchResultResponse response = service.getMatchResult(gameId, userId);

        assertThat(response.gameId()).isEqualTo(gameId);
        assertThat(response.state()).isEqualTo(GameStates.FINISHED);
        assertThat(response.winnerId()).isEqualTo(player0);
        assertThat(response.winnerUsername()).isEqualTo("alice");
        assertThat(response.loserUsername()).isEqualTo("bob");
        assertThat(response.finalKeepHp()).containsExactly(12, 0);
        assertThat(response.finalRound()).isEqualTo(3);
        assertThat(response.durationSeconds()).isEqualTo(330);
        assertThat(response.finishedAt()).isEqualTo(finished);
    }

    @Test
    void getMatchResult_draw_nullsWinnerFields() {
        Instant started = Instant.parse("2024-06-01T12:00:00Z");
        Instant finished = Instant.parse("2024-06-01T12:02:00Z");
        Game game = finishedGame(started, finished, null, 8);

        when(gameRepository.findById(gameId)).thenReturn(Optional.of(game));
        GamePlayer seat0 = new GamePlayer(gameId, player0, 0);
        seat0.setKeepHp(0);
        GamePlayer seat1 = new GamePlayer(gameId, player1, 1);
        seat1.setKeepHp(0);
        when(gamePlayerRepository.findByGameIdOrderBySeatAsc(gameId)).thenReturn(List.of(seat0, seat1));

        MatchResultResponse response = service.getMatchResult(gameId, userId);

        assertThat(response.winnerId()).isNull();
        assertThat(response.winnerUsername()).isNull();
        assertThat(response.loserUsername()).isNull();
        assertThat(response.finalKeepHp()).containsExactly(0, 0);
        assertThat(response.finalRound()).isEqualTo(8);
        assertThat(response.durationSeconds()).isEqualTo(120);
    }

    private Game finishedGame(Instant started, Instant finished, UUID winnerId, int finalRound) {
        Game game = Game.create(player0);
        ReflectionTestUtils.setField(game, "id", gameId);
        ReflectionTestUtils.setField(game, "startedAt", started);
        ReflectionTestUtils.setField(game, "createdAt", started);
        game.setPlayer2Id(player1);
        game.setState(GameStates.FINISHED);
        game.setWinnerId(winnerId);
        game.setCurrentRound(finalRound);
        game.setFinishedAt(finished);
        return game;
    }
}
