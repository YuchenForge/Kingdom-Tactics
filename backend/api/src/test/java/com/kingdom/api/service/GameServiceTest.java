package com.kingdom.api.service;

import com.kingdom.api.dto.GameResponse;
import com.kingdom.api.entity.Game;
import com.kingdom.api.entity.GamePlayer;
import com.kingdom.api.entity.GameStates;
import com.kingdom.api.entity.Round;
import com.kingdom.api.entity.RoundPlan;
import com.kingdom.api.entity.User;
import com.kingdom.api.exception.AlreadyInGameException;
import com.kingdom.api.exception.GameFullException;
import com.kingdom.api.exception.GameNotFoundException;
import com.kingdom.api.exception.NotGameParticipantException;
import com.kingdom.api.repository.GamePlayerRepository;
import com.kingdom.api.repository.GameRepository;
import com.kingdom.api.repository.RoundPlanRepository;
import com.kingdom.api.repository.RoundRepository;
import com.kingdom.api.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GameServiceTest {

    @Mock
    private GameRepository gameRepository;

    @Mock
    private GamePlayerRepository gamePlayerRepository;

    @Mock
    private RoundRepository roundRepository;

    @Mock
    private RoundPlanRepository roundPlanRepository;

    @Mock
    private UserRepository userRepository;

    private GameService gameService;

    private final UUID gameId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID creatorId = UUID.randomUUID();
    private final UUID joinerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        gameService = new GameService(
                gameRepository,
                gamePlayerRepository,
                roundRepository,
                roundPlanRepository,
                userRepository);
    }

    @Test
    void assertParticipant_rejectsMissingGame() {
        when(gameRepository.existsById(gameId)).thenReturn(false);

        assertThatThrownBy(() -> gameService.assertParticipant(gameId, userId))
                .isInstanceOf(GameNotFoundException.class);

        verifyNoInteractions(gamePlayerRepository);
    }

    @Test
    void assertParticipant_rejectsNonMember() {
        when(gameRepository.existsById(gameId)).thenReturn(true);
        when(gamePlayerRepository.existsByGameIdAndPlayerId(gameId, userId)).thenReturn(false);

        assertThatThrownBy(() -> gameService.assertParticipant(gameId, userId))
                .isInstanceOf(NotGameParticipantException.class);
    }

    @Test
    void assertParticipant_allowsMember() {
        when(gameRepository.existsById(gameId)).thenReturn(true);
        when(gamePlayerRepository.existsByGameIdAndPlayerId(gameId, userId)).thenReturn(true);

        assertThatCode(() -> gameService.assertParticipant(gameId, userId))
                .doesNotThrowAnyException();
    }

    @Test
    void createGame_setsWaitingAndSavesSeat0() {
        User creator = user("alice", creatorId);
        when(gameRepository.save(any(Game.class))).thenAnswer(invocation -> {
            Game game = invocation.getArgument(0);
            ReflectionTestUtils.setField(game, "id", gameId);
            ReflectionTestUtils.invokeMethod(game, "onCreate");
            return game;
        });
        when(gamePlayerRepository.save(any(GamePlayer.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(gamePlayerRepository.findByGameIdOrderBySeatAsc(gameId))
                .thenReturn(List.of(new GamePlayer(gameId, creatorId, 0)));
        when(userRepository.findById(creatorId)).thenReturn(Optional.of(creator));

        GameResponse response = gameService.createGame(creatorId);

        assertThat(response.gameId()).isEqualTo(gameId);
        assertThat(response.state()).isEqualTo(GameStates.WAITING_FOR_PLAYERS);
        assertThat(response.currentRound()).isZero();
        assertThat(response.planningDeadline()).isNull();

        ArgumentCaptor<GamePlayer> seatCaptor = ArgumentCaptor.forClass(GamePlayer.class);
        verify(gamePlayerRepository).save(seatCaptor.capture());
        assertThat(seatCaptor.getValue().getSeat()).isZero();
        assertThat(seatCaptor.getValue().getPlayerId()).isEqualTo(creatorId);
        assertThat(seatCaptor.getValue().getGameId()).isEqualTo(gameId);
    }

    @Test
    void joinGame_movesToPreparationWithRoundAndTwoPlans() {
        Game game = waitingGame(creatorId);
        UUID roundId = UUID.randomUUID();
        User creator = user("alice", creatorId);
        User joiner = user("bob", joinerId);

        when(gameRepository.findById(gameId)).thenReturn(Optional.of(game));
        when(gamePlayerRepository.existsByGameIdAndPlayerId(gameId, joinerId)).thenReturn(false);
        when(gamePlayerRepository.countByGameId(gameId)).thenReturn(1L);
        when(gamePlayerRepository.saveAndFlush(any(GamePlayer.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(roundRepository.save(any(Round.class))).thenAnswer(invocation -> {
            Round round = invocation.getArgument(0);
            ReflectionTestUtils.setField(round, "id", roundId);
            ReflectionTestUtils.invokeMethod(round, "onCreate");
            return round;
        });
        when(roundPlanRepository.save(any(RoundPlan.class))).thenAnswer(invocation -> invocation.getArgument(0));

        when(gamePlayerRepository.findByGameIdOrderBySeatAsc(gameId)).thenReturn(List.of(
                new GamePlayer(gameId, creatorId, 0),
                new GamePlayer(gameId, joinerId, 1)));
        when(userRepository.findById(creatorId)).thenReturn(Optional.of(creator));
        when(userRepository.findById(joinerId)).thenReturn(Optional.of(joiner));
        when(roundRepository.findByGameIdAndRoundNumber(gameId, 1)).thenAnswer(invocation -> {
            Round round = new Round(gameId, 1, GameStates.PREPARATION, Instant.now().plusSeconds(45));
            ReflectionTestUtils.setField(round, "id", roundId);
            return Optional.of(round);
        });
        when(roundPlanRepository.findByRoundIdAndPlayerId(eq(roundId), eq(creatorId)))
                .thenReturn(Optional.of(new RoundPlan(roundId, creatorId, GameService.STARTING_GOLD)));
        when(roundPlanRepository.findByRoundIdAndPlayerId(eq(roundId), eq(joinerId)))
                .thenReturn(Optional.of(new RoundPlan(roundId, joinerId, GameService.STARTING_GOLD)));

        GameResponse response = gameService.joinGame(gameId, joinerId);

        assertThat(response.state()).isEqualTo(GameStates.PREPARATION);
        assertThat(response.currentRound()).isEqualTo(1);
        assertThat(game.getPlayer2Id()).isEqualTo(joinerId);
        assertThat(game.getState()).isEqualTo(GameStates.PREPARATION);

        ArgumentCaptor<RoundPlan> planCaptor = ArgumentCaptor.forClass(RoundPlan.class);
        verify(roundPlanRepository, org.mockito.Mockito.times(2)).save(planCaptor.capture());
        assertThat(planCaptor.getAllValues()).extracting(RoundPlan::getGold)
                .containsExactly(GameService.STARTING_GOLD, GameService.STARTING_GOLD);
        assertThat(planCaptor.getAllValues()).extracting(RoundPlan::getPlayerId)
                .containsExactlyInAnyOrder(creatorId, joinerId);
    }

    @Test
    void joinGame_whenFull_throwsGameFullException() {
        Game game = waitingGame(creatorId);
        game.setPlayer2Id(UUID.randomUUID());
        game.setState(GameStates.PREPARATION);

        when(gameRepository.findById(gameId)).thenReturn(Optional.of(game));
        when(gamePlayerRepository.existsByGameIdAndPlayerId(gameId, joinerId)).thenReturn(false);

        assertThatThrownBy(() -> gameService.joinGame(gameId, joinerId))
                .isInstanceOf(GameFullException.class);

        verify(gamePlayerRepository, never()).saveAndFlush(any());
    }

    @Test
    void joinGame_whenAlreadyIn_throwsAlreadyInGameException() {
        Game game = waitingGame(creatorId);
        when(gameRepository.findById(gameId)).thenReturn(Optional.of(game));
        when(gamePlayerRepository.existsByGameIdAndPlayerId(gameId, creatorId)).thenReturn(true);

        assertThatThrownBy(() -> gameService.joinGame(gameId, creatorId))
                .isInstanceOf(AlreadyInGameException.class);

        verify(gamePlayerRepository, never()).saveAndFlush(any());
    }

    @Test
    void getState_asNonParticipant_throwsNotGameParticipantException() {
        when(gameRepository.existsById(gameId)).thenReturn(true);
        when(gamePlayerRepository.existsByGameIdAndPlayerId(gameId, userId)).thenReturn(false);

        assertThatThrownBy(() -> gameService.getState(gameId, userId))
                .isInstanceOf(NotGameParticipantException.class);
    }

    private Game waitingGame(UUID player1Id) {
        Game game = Game.create(player1Id);
        ReflectionTestUtils.setField(game, "id", gameId);
        ReflectionTestUtils.invokeMethod(game, "onCreate");
        return game;
    }

    private static User user(String username, UUID id) {
        User user = new User(username, username + "@test.com", "hash".getBytes(StandardCharsets.UTF_8));
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
