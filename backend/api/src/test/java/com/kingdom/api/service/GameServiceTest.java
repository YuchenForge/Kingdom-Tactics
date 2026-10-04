package com.kingdom.api.service;

import com.kingdom.api.dto.GameResponse;
import com.kingdom.api.dto.GameStateResponse;
import com.kingdom.api.entity.Game;
import com.kingdom.api.entity.GamePlayer;
import com.kingdom.api.entity.GameStates;
import com.kingdom.api.entity.Round;
import com.kingdom.api.entity.RoundPlan;
import com.kingdom.api.entity.User;
import com.kingdom.api.exception.AlreadyInGameException;
import com.kingdom.api.exception.GameFullException;
import com.kingdom.api.exception.GameNotFoundException;
import com.kingdom.api.exception.GameNotReadyException;
import com.kingdom.api.exception.NotGameParticipantException;
import com.kingdom.api.repository.GamePlayerRepository;
import com.kingdom.api.repository.GameRepository;
import com.kingdom.api.repository.RoundPlanRepository;
import com.kingdom.api.repository.RoundRepository;
import com.kingdom.api.repository.UserRepository;
import com.kingdom.engine.planning.PlanningShop;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GameServiceTest {

    @Test
    void combatFormations_arePrivateDuringPlanning_andUseEngineCoordinates() {
        var board0 = new com.kingdom.engine.planning.PlanningUnit[4][4];
        var board1 = new com.kingdom.engine.planning.PlanningUnit[4][4];
        board0[1][2] = new com.kingdom.engine.planning.PlanningUnit("a", "Squire", 2);
        board1[2][1] = new com.kingdom.engine.planning.PlanningUnit("b", "Archer", 1);
        var lane = new com.kingdom.engine.planning.PlanningUnit[5];
        lane[0] = new com.kingdom.engine.planning.PlanningUnit("reserve", "Squire", 1);
        var a = new com.kingdom.engine.planning.PlanningState(10, true, PlanningShop.empty(), lane, board0, 1);
        var b = new com.kingdom.engine.planning.PlanningState(10, true, PlanningShop.empty(),
                new com.kingdom.engine.planning.PlanningUnit[5], board1, 1);
        assertThat(GameService.combatUnits(GameStates.PREPARATION, a, 0, b, 1)).isEmpty();
        for (String phase : List.of(GameStates.LOCKED, GameStates.RESOLVING, GameStates.ROUND_RESULT)) {
            assertThat(GameService.combatUnits(phase, a, 0, b, 1)).containsExactly(
                    new com.kingdom.api.dto.CombatUnitDto("a", "Squire", 2, 0, 2, 1),
                    new com.kingdom.api.dto.CombatUnitDto("b", "Archer", 1, 1, 2, 5));
        }
    }

    private static final Instant FIXED_NOW = Instant.parse("2024-06-01T12:00:00Z");

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

    @Mock
    private ShopService shopService;

    @Mock
    private PlanningDeadlineService planningDeadlineService;

    private GameService gameService;

    private final UUID gameId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID creatorId = UUID.randomUUID();
    private final UUID joinerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        lenient().when(txManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(mock(TransactionStatus.class));

        gameService = new GameService(
                gameRepository,
                gamePlayerRepository,
                roundRepository,
                roundPlanRepository,
                userRepository,
                shopService,
                planningDeadlineService,
                Clock.fixed(FIXED_NOW, ZoneOffset.UTC),
                txManager);
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
        verifyNoInteractions(shopService);
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

        ArgumentCaptor<Round> roundCaptor = ArgumentCaptor.forClass(Round.class);
        verify(shopService).createShopsForRound(roundCaptor.capture(), eq(gameId), eq(creatorId), eq(joinerId));
        assertThat(roundCaptor.getValue().getId()).isEqualTo(roundId);
        assertThat(roundCaptor.getValue().getRoundNumber()).isEqualTo(1);
        assertThat(roundCaptor.getValue().getPlanningDeadline())
                .isEqualTo(FIXED_NOW.plusSeconds(GameService.PLANNING_SECONDS));
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
    void joinGame_creatorJoiningOwnGame_throwsAlreadyInGame() {
        Game game = waitingGame(creatorId);
        when(gameRepository.findById(gameId)).thenReturn(Optional.of(game));
        // Seat row missing, but player1Id still matches — second OR branch
        when(gamePlayerRepository.existsByGameIdAndPlayerId(gameId, creatorId)).thenReturn(false);

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

    @Test
    void getState_whileWaiting_throwsGameNotReady() {
        Game game = waitingGame(creatorId);
        when(gameRepository.existsById(gameId)).thenReturn(true);
        when(gamePlayerRepository.existsByGameIdAndPlayerId(gameId, creatorId)).thenReturn(true);
        when(gameRepository.findById(gameId)).thenReturn(Optional.of(game));

        assertThatThrownBy(() -> gameService.getState(gameId, creatorId))
                .isInstanceOf(GameNotReadyException.class);

        verify(planningDeadlineService).autoLockIfDeadlinePassed(gameId);
    }

    @Test
    void getState_whenPreparation_attemptsDeadlineFinalize() {
        UUID roundId = UUID.randomUUID();
        Game locked = waitingGame(creatorId);
        locked.setPlayer2Id(joinerId);
        locked.setState(GameStates.LOCKED);
        locked.setCurrentRound(1);

        Round round = new Round(gameId, 1, GameStates.LOCKED, Instant.now().minusSeconds(1));
        ReflectionTestUtils.setField(round, "id", roundId);

        RoundPlan yourPlan = new RoundPlan(roundId, creatorId, 10);
        yourPlan.setLocked(true);
        RoundPlan opponentPlan = new RoundPlan(roundId, joinerId, 10);
        opponentPlan.setLocked(true);

        when(gameRepository.existsById(gameId)).thenReturn(true);
        when(gamePlayerRepository.existsByGameIdAndPlayerId(gameId, creatorId)).thenReturn(true);
        when(gameRepository.findById(gameId)).thenReturn(Optional.of(locked));
        when(gamePlayerRepository.findByGameIdAndPlayerId(gameId, creatorId))
                .thenReturn(Optional.of(new GamePlayer(gameId, creatorId, 0)));
        when(gamePlayerRepository.findByGameIdAndPlayerId(gameId, joinerId))
                .thenReturn(Optional.of(new GamePlayer(gameId, joinerId, 1)));
        when(roundRepository.findByGameIdAndRoundNumber(gameId, 1)).thenReturn(Optional.of(round));
        when(roundRepository.findLatestResolvedRoundNumber(gameId)).thenReturn(Optional.empty());
        when(roundPlanRepository.findByRoundIdAndPlayerId(roundId, creatorId))
                .thenReturn(Optional.of(yourPlan));
        when(roundPlanRepository.findByRoundIdAndPlayerId(roundId, joinerId))
                .thenReturn(Optional.of(opponentPlan));
        when(shopService.loadShop(roundId, creatorId)).thenReturn(PlanningShop.empty());

        GameStateResponse response = gameService.getState(gameId, creatorId);

        verify(planningDeadlineService).autoLockIfDeadlinePassed(gameId);
        assertThat(response.state()).isEqualTo(GameStates.LOCKED);
        assertThat(response.latestResolvedRound()).isNull();
        assertThat(response.isLocked()).isTrue();
        assertThat(response.opponentIsLocked()).isTrue();
    }

    @Test
    void getState_whileResolving_returnsLiveKeepHpAndLatestResolved() {
        UUID roundId = UUID.randomUUID();
        Game game = twoPlayerGame(GameStates.RESOLVING, 2);
        Round round = new Round(gameId, 2, GameStates.RESOLVING, Instant.now().plusSeconds(45));
        ReflectionTestUtils.setField(round, "id", roundId);

        GamePlayer you = new GamePlayer(gameId, creatorId, 0);
        you.setKeepHp(17); // post-TX2 live Keep HP
        GamePlayer opponent = new GamePlayer(gameId, joinerId, 1);
        opponent.setKeepHp(20);

        stubParticipantState(game, round, you, opponent, true, true);
        when(roundRepository.findLatestResolvedRoundNumber(gameId)).thenReturn(Optional.of(1));

        GameStateResponse response = gameService.getState(gameId, creatorId);

        assertThat(response.state()).isEqualTo(GameStates.RESOLVING);
        assertThat(response.currentRound()).isEqualTo(2);
        assertThat(response.latestResolvedRound()).isEqualTo(1);
        assertThat(response.yourKeepHp()).isEqualTo(17);
        assertThat(response.opponentKeepHp()).isEqualTo(20);
        verify(planningDeadlineService).autoLockIfDeadlinePassed(gameId);
    }

    @Test
    void getState_whenFinished_stillReturnableWithLatestResolvedRound() {
        UUID roundId = UUID.randomUUID();
        Game game = twoPlayerGame(GameStates.FINISHED, 8);
        Round round = new Round(gameId, 8, GameStates.ROUND_RESULT, Instant.now().minusSeconds(60));
        ReflectionTestUtils.setField(round, "id", roundId);

        GamePlayer you = new GamePlayer(gameId, creatorId, 0);
        you.setKeepHp(12);
        GamePlayer opponent = new GamePlayer(gameId, joinerId, 1);
        opponent.setKeepHp(0);

        stubParticipantState(game, round, you, opponent, true, true);
        when(roundRepository.findLatestResolvedRoundNumber(gameId)).thenReturn(Optional.of(8));

        GameStateResponse response = gameService.getState(gameId, creatorId);

        assertThat(response.state()).isEqualTo(GameStates.FINISHED);
        assertThat(response.latestResolvedRound()).isEqualTo(8);
        assertThat(response.yourKeepHp()).isEqualTo(12);
        assertThat(response.opponentKeepHp()).isZero();
        verify(planningDeadlineService).autoLockIfDeadlinePassed(gameId);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "PREPARATION,true", "LOCKED,true", "RESOLVING,true", "FINISHED,true",
            "ROUND_RESULT,false", "ROUND_RESULT,true"
    })
    void getState_exposesPersistedTimingOnlyForScheduledRoundResults(String phase, boolean scheduled) {
        UUID roundId = UUID.randomUUID();
        Game game = twoPlayerGame(phase, 2);
        Round round = new Round(gameId, 2, phase, FIXED_NOW.plusSeconds(45));
        ReflectionTestUtils.setField(round, "id", roundId);
        // Already expired: GET must return the stored schedule, never extend or hide it.
        Instant startsAt = FIXED_NOW.minusSeconds(10);
        if (scheduled) round.setCombatPresentation(startsAt, startsAt.plusSeconds(5),
                startsAt.plusSeconds(7), 250);
        stubParticipantState(game, round, new GamePlayer(gameId, creatorId, 0),
                new GamePlayer(gameId, joinerId, 1), true, true);
        when(roundRepository.findLatestResolvedRoundNumber(gameId)).thenReturn(Optional.of(1));

        GameStateResponse response = gameService.getState(gameId, creatorId);
        assertThat(response.serverTime()).isEqualTo(FIXED_NOW);
        if (GameStates.ROUND_RESULT.equals(phase) && scheduled) {
            assertThat(response.combatPresentation()).isEqualTo(new com.kingdom.api.dto.CombatPresentationDto(
                    2, startsAt, startsAt.plusSeconds(5), startsAt.plusSeconds(7), 250));
            assertThat(gameService.getState(gameId, creatorId).combatPresentation())
                    .isEqualTo(response.combatPresentation());
        } else {
            assertThat(response.combatPresentation()).isNull();
        }
        verify(roundRepository, never()).save(any());
    }

    @Test
    void getGame_whenPreparation_attemptsDeadlineFinalize() {
        Game locked = waitingGame(creatorId);
        locked.setPlayer2Id(joinerId);
        locked.setState(GameStates.LOCKED);
        locked.setCurrentRound(1);

        UUID roundId = UUID.randomUUID();
        when(gameRepository.existsById(gameId)).thenReturn(true);
        when(gamePlayerRepository.existsByGameIdAndPlayerId(gameId, creatorId)).thenReturn(true);
        when(gameRepository.findById(gameId)).thenReturn(Optional.of(locked));
        when(gamePlayerRepository.findByGameIdOrderBySeatAsc(gameId)).thenReturn(List.of(
                new GamePlayer(gameId, creatorId, 0),
                new GamePlayer(gameId, joinerId, 1)));
        when(userRepository.findById(creatorId)).thenReturn(Optional.of(user("alice", creatorId)));
        when(userRepository.findById(joinerId)).thenReturn(Optional.of(user("bob", joinerId)));
        when(roundRepository.findByGameIdAndRoundNumber(gameId, 1)).thenReturn(Optional.of(
                new Round(gameId, 1, GameStates.LOCKED, Instant.now().minusSeconds(1))));
        when(roundPlanRepository.findByRoundIdAndPlayerId(any(), eq(creatorId)))
                .thenReturn(Optional.of(new RoundPlan(roundId, creatorId, 10)));
        when(roundPlanRepository.findByRoundIdAndPlayerId(any(), eq(joinerId)))
                .thenReturn(Optional.of(new RoundPlan(roundId, joinerId, 10)));

        gameService.getGame(gameId, creatorId);

        verify(planningDeadlineService).autoLockIfDeadlinePassed(gameId);
    }

    private Game waitingGame(UUID player1Id) {
        Game game = Game.create(player1Id);
        ReflectionTestUtils.setField(game, "id", gameId);
        ReflectionTestUtils.invokeMethod(game, "onCreate");
        return game;
    }

    private Game twoPlayerGame(String state, int currentRound) {
        Game game = waitingGame(creatorId);
        game.setPlayer2Id(joinerId);
        game.setState(state);
        game.setCurrentRound(currentRound);
        return game;
    }

    private void stubParticipantState(
            Game game,
            Round round,
            GamePlayer you,
            GamePlayer opponent,
            boolean yourLocked,
            boolean opponentLocked) {
        UUID roundId = round.getId();
        RoundPlan yourPlan = new RoundPlan(roundId, you.getPlayerId(), 10);
        yourPlan.setLocked(yourLocked);
        RoundPlan opponentPlan = new RoundPlan(roundId, opponent.getPlayerId(), 10);
        opponentPlan.setLocked(opponentLocked);

        when(gameRepository.existsById(gameId)).thenReturn(true);
        when(gamePlayerRepository.existsByGameIdAndPlayerId(gameId, you.getPlayerId())).thenReturn(true);
        when(gameRepository.findById(gameId)).thenReturn(Optional.of(game));
        when(gamePlayerRepository.findByGameIdAndPlayerId(gameId, you.getPlayerId()))
                .thenReturn(Optional.of(you));
        when(gamePlayerRepository.findByGameIdAndPlayerId(gameId, opponent.getPlayerId()))
                .thenReturn(Optional.of(opponent));
        when(roundRepository.findByGameIdAndRoundNumber(gameId, game.getCurrentRound()))
                .thenReturn(Optional.of(round));
        when(roundPlanRepository.findByRoundIdAndPlayerId(roundId, you.getPlayerId()))
                .thenReturn(Optional.of(yourPlan));
        when(roundPlanRepository.findByRoundIdAndPlayerId(roundId, opponent.getPlayerId()))
                .thenReturn(Optional.of(opponentPlan));
        when(shopService.loadShop(roundId, you.getPlayerId())).thenReturn(PlanningShop.empty());
    }

    private static User user(String username, UUID id) {
        User user = new User(username, username + "@test.com", "hash".getBytes(StandardCharsets.UTF_8));
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
