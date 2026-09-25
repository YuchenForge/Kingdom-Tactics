package com.kingdom.api.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import org.mockito.Mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import com.kingdom.api.dto.CommandResponse;
import com.kingdom.api.entity.Command;
import com.kingdom.api.entity.Game;
import com.kingdom.api.entity.GameStates;
import com.kingdom.api.entity.Round;
import com.kingdom.api.entity.RoundPlan;
import com.kingdom.api.exception.ConflictException;
import com.kingdom.api.exception.GameNotReadyException;
import com.kingdom.api.exception.PlanningCommandException;
import com.kingdom.api.exception.RoundLockedException;
import com.kingdom.api.exception.WrongGameStateException;
import com.kingdom.api.repository.CommandRepository;
import com.kingdom.api.repository.GameRepository;
import com.kingdom.api.repository.RoundPlanRepository;
import com.kingdom.api.repository.RoundRepository;
import com.kingdom.engine.planning.CommandApplier;
import com.kingdom.engine.planning.PlanningError;
import com.kingdom.engine.planning.PlanningShop;

@ExtendWith(MockitoExtension.class)
class CommandServiceTest {

    // Mock dependencies
    @Mock
    private GameService gameService;
    @Mock
    private GameRepository gameRepository;
    @Mock
    private RoundRepository roundRepository;
    @Mock
    private RoundPlanRepository roundPlanRepository;
    @Mock
    private CommandRepository commandRepository;
    @Mock
    private ShopService shopService;
    @Mock
    private PlanningDeadlineService planningDeadlineService;
    @Mock
    private PlatformTransactionManager transactionManager;
    @Mock
    private TransactionStatus transactionStatus;

    private CommandService commandService;

    // Test data: stable ids for testing
    private final UUID gameId = UUID.randomUUID();
    private final UUID roundId = UUID.randomUUID();
    private final UUID planId = UUID.randomUUID();
    private final UUID aliceId = UUID.randomUUID();
    private final UUID bobId = UUID.randomUUID();
    private final UUID key = UUID.randomUUID();

    private Game game;
    private Round round;
    private RoundPlan plan;

    @BeforeEach
    void setUp() {
        lenient().when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(transactionStatus);
        lenient().when(planningDeadlineService.enforceDeadlineOrAutoLock(any(), any()))
                .thenReturn(false);

        commandService = new CommandService(
                gameService,
                gameRepository,
                roundRepository,
                roundPlanRepository,
                commandRepository,
                shopService,
                new CommandApplier(() -> "unit-test-1"),
                planningDeadlineService,
                Clock.fixed(Instant.parse("2024-06-01T12:00:00Z"), ZoneOffset.UTC),
                transactionManager);

        // Create a game
        game = Game.create(aliceId);
        ReflectionTestUtils.setField(game, "id", gameId);
        game.setPlayer2Id(bobId);
        game.setState(GameStates.PREPARATION);
        game.setCurrentRound(1);

        // Create a round
        round = new Round(gameId, 1, GameStates.PREPARATION, Instant.now().plusSeconds(45));
        ReflectionTestUtils.setField(round, "id", roundId);

        // Create a plan
        plan = new RoundPlan(roundId, aliceId, 10);
        ReflectionTestUtils.setField(plan, "id", planId);
    }

    @Test
    void buy_persistsPlanShopAndCommand() {
        stubHappyPath();
        stubPersists();
        when(shopService.loadShop(roundId, aliceId))
                .thenReturn(PlanningShop.of("Squire", "Mage", "Ranger"));
        when(commandRepository.countByRoundPlanId(planId)).thenReturn(0);

        CommandResponse response = commandService.buy(gameId, 1, aliceId, key, 0);

        assertThat(response.success()).isTrue();
        assertThat(response.gold()).isEqualTo(9);
        assertThat(response.lane().get(0).unitType()).isEqualTo("Squire");
        assertThat(plan.getGold()).isEqualTo(9);
        verify(shopService).consumeOffer(roundId, aliceId, 0);
        verify(roundPlanRepository).saveAndFlush(plan);

        ArgumentCaptor<Command> commandCaptor = ArgumentCaptor.forClass(Command.class);
        verify(commandRepository).saveAndFlush(commandCaptor.capture());
        assertThat(commandCaptor.getValue().getCommandType()).isEqualTo(CommandService.TYPE_BUY);
        assertThat(commandCaptor.getValue().getIdempotencyKey()).isEqualTo(key);
        assertThat(commandCaptor.getValue().getSequenceNumber()).isZero();
    }

    @Test
    void buy_optimisticLockOnPlan_throwsConflict() {
        stubHappyPath();
        when(shopService.loadShop(roundId, aliceId))
                .thenReturn(PlanningShop.of("Squire", "Mage", "Ranger"));
        when(roundPlanRepository.saveAndFlush(any(RoundPlan.class)))
                .thenThrow(new OptimisticLockingFailureException("stale version"));

        assertThatThrownBy(() -> commandService.buy(gameId, 1, aliceId, key, 0))
                .isInstanceOf(ConflictException.class)
                .extracting(ex -> ((ConflictException) ex).getCode())
                .isEqualTo("CONFLICT");
    }

    @Test
    void buy_duplicateIdempotencyKey_returnsCommittedSnapshot() {
        stubHappyPath();
        when(shopService.loadShop(roundId, aliceId))
                .thenReturn(PlanningShop.of("Squire", "Mage", "Ranger"));
        when(roundPlanRepository.saveAndFlush(any(RoundPlan.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(commandRepository.countByRoundPlanId(planId)).thenReturn(0);
        when(commandRepository.saveAndFlush(any(Command.class)))
                .thenThrow(new DataIntegrityViolationException(
                        "ERROR: duplicate key value violates unique constraint "
                                + "\"commands_round_plan_id_idempotency_key_key\""));
        // Recovery TX confirms the winner's row, then returns snapshot.
        when(commandRepository.findByRoundPlanIdAndIdempotencyKey(planId, key))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(new Command(
                        planId, 0, CommandService.TYPE_BUY, Map.of("shopSlot", 0), key)));

        CommandResponse response = commandService.buy(gameId, 1, aliceId, key, 0);

        assertThat(response.success()).isTrue();
        assertThat(response.gold()).isEqualTo(9);
    }

    @Test
    void buy_unrelatedIntegrityViolation_propagates() {
        stubHappyPath();
        when(shopService.loadShop(roundId, aliceId))
                .thenReturn(PlanningShop.of("Squire", "Mage", "Ranger"));
        when(roundPlanRepository.saveAndFlush(any(RoundPlan.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(commandRepository.countByRoundPlanId(planId)).thenReturn(0);
        when(commandRepository.saveAndFlush(any(Command.class)))
                .thenThrow(new DataIntegrityViolationException(
                        "ERROR: duplicate key value violates unique constraint "
                                + "\"commands_round_plan_id_sequence_number_key\""));

        assertThatThrownBy(() -> commandService.buy(gameId, 1, aliceId, key, 0))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("sequence_number");
    }

    @Test
    void buy_idempotencyConstraint_withoutCommittedKey_propagates() {
        stubHappyPath();
        when(shopService.loadShop(roundId, aliceId))
                .thenReturn(PlanningShop.of("Squire", "Mage", "Ranger"));
        when(roundPlanRepository.saveAndFlush(any(RoundPlan.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(commandRepository.countByRoundPlanId(planId)).thenReturn(0);
        when(commandRepository.saveAndFlush(any(Command.class)))
                .thenThrow(new DataIntegrityViolationException(
                        "commands_round_plan_id_idempotency_key_key"));
        // Constraint name matches, but key never landed — do not invent success.
        when(commandRepository.findByRoundPlanIdAndIdempotencyKey(planId, key))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> commandService.buy(gameId, 1, aliceId, key, 0))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void buy_optimisticLock_whenKeyCommitted_returnsSnapshot() {
        stubHappyPath();
        when(shopService.loadShop(roundId, aliceId))
                .thenReturn(PlanningShop.of("Squire", "Mage", "Ranger"));
        when(roundPlanRepository.saveAndFlush(any(RoundPlan.class)))
                .thenThrow(new OptimisticLockingFailureException("stale version"));
        when(commandRepository.findByRoundPlanIdAndIdempotencyKey(planId, key))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(new Command(
                        planId, 0, CommandService.TYPE_BUY, Map.of("shopSlot", 0), key)));

        CommandResponse response = commandService.buy(gameId, 1, aliceId, key, 0);

        assertThat(response.success()).isTrue();
        assertThat(response.gold()).isEqualTo(9);
    }

    @ParameterizedTest
    @CsvSource({
            "PREPARATION, PREPARATION",
            "LOCKED, LOCKED"
    })
    void buy_idempotentRetry_doesNotReapply(String gameState, String roundState) {
        game.setState(gameState);
        round.setState(roundState);
        when(gameRepository.findById(gameId)).thenReturn(Optional.of(game));
        when(roundRepository.findByGameIdAndRoundNumber(gameId, 1)).thenReturn(Optional.of(round));
        when(roundPlanRepository.findByRoundIdAndPlayerId(roundId, aliceId)).thenReturn(Optional.of(plan));
        when(commandRepository.findByRoundPlanIdAndIdempotencyKey(planId, key))
                .thenReturn(Optional.of(new Command(planId, 0, CommandService.TYPE_BUY, null, key)));
        when(shopService.loadShop(roundId, aliceId))
                .thenReturn(PlanningShop.of("Squire", "Mage", "Ranger"));

        CommandResponse response = commandService.buy(gameId, 1, aliceId, key, 0);

        assertThat(response.success()).isTrue();
        assertThat(response.gold()).isEqualTo(10);
        verify(shopService, never()).consumeOffer(any(), any(), any(Integer.class));
        verify(commandRepository, never()).saveAndFlush(any());
        verify(roundPlanRepository, never()).saveAndFlush(any());
    }

    @Test
    void buy_pastDeadline_throwsDeadlinePassedWithoutApplying() {
        stubHappyPath();
        when(planningDeadlineService.enforceDeadlineOrAutoLock(game, round)).thenReturn(true);

        assertThatThrownBy(() -> commandService.buy(gameId, 1, aliceId, key, 0))
                .isInstanceOf(PlanningCommandException.class)
                .satisfies(ex -> {
                    PlanningCommandException pce = (PlanningCommandException) ex;
                    assertThat(pce.getError()).isEqualTo(PlanningError.DEADLINE_PASSED);
                    assertThat(pce.httpStatus()).isEqualTo(423);
                    assertThat(pce.errorCode()).isEqualTo("DEADLINE_PASSED");
                });

        verify(shopService, never()).loadShop(any(), any());
        verify(shopService, never()).consumeOffer(any(), any(), any(Integer.class));
        verify(commandRepository, never()).saveAndFlush(any());
    }

    @Test
    void buy_pastDeadline_idempotentRetry_returnsLockedSnapshot() {
        RoundPlan lockedPlan = new RoundPlan(roundId, aliceId, 9);
        ReflectionTestUtils.setField(lockedPlan, "id", planId);
        lockedPlan.setLocked(true);

        when(gameRepository.findById(gameId)).thenReturn(Optional.of(game));
        when(roundRepository.findByGameIdAndRoundNumber(gameId, 1)).thenReturn(Optional.of(round));
        // Initial load is unlocked; refresh + committed snapshot see the deadline lock.
        when(roundPlanRepository.findByRoundIdAndPlayerId(roundId, aliceId))
                .thenReturn(Optional.of(plan))
                .thenReturn(Optional.of(lockedPlan));
        when(planningDeadlineService.enforceDeadlineOrAutoLock(game, round)).thenReturn(true);
        when(commandRepository.findByRoundPlanIdAndIdempotencyKey(planId, key))
                .thenReturn(Optional.of(new Command(planId, 0, CommandService.TYPE_BUY, null, key)));
        when(shopService.loadShop(roundId, aliceId))
                .thenReturn(PlanningShop.of("Squire", "Mage", "Ranger").withSlotSold(0));

        CommandResponse response = commandService.buy(gameId, 1, aliceId, key, 0);

        assertThat(response.success()).isTrue();
        assertThat(response.gold()).isEqualTo(9);
        assertThat(response.isLocked()).isTrue();
        verify(shopService, never()).consumeOffer(any(), any(), any(Integer.class));
    }

    @Test
    void buy_emptyShopAfterConcurrentIdempotentCommit_returnsSnapshot() {
        RoundPlan committedPlan = new RoundPlan(roundId, aliceId, 9);
        ReflectionTestUtils.setField(committedPlan, "id", planId);
        Command committed = new Command(
                planId, 0, CommandService.TYPE_BUY, Map.of("shopSlot", 0), key);

        when(gameRepository.findById(gameId)).thenReturn(Optional.of(game));
        when(roundRepository.findByGameIdAndRoundNumber(gameId, 1)).thenReturn(Optional.of(round));
        when(roundPlanRepository.findByRoundIdAndPlayerId(roundId, aliceId))
                .thenReturn(Optional.of(plan))
                .thenReturn(Optional.of(committedPlan));
        // Miss on first lookup; recovery sees the winner's row.
        when(commandRepository.findByRoundPlanIdAndIdempotencyKey(planId, key))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(committed));
        when(shopService.loadShop(roundId, aliceId))
                .thenReturn(PlanningShop.of("Squire", "Mage", "Ranger").withSlotSold(0));

        CommandResponse response = commandService.buy(gameId, 1, aliceId, key, 0);

        assertThat(response.success()).isTrue();
        assertThat(response.gold()).isEqualTo(9);
        verify(shopService, never()).consumeOffer(any(), any(), any(Integer.class));
        verify(commandRepository, never()).saveAndFlush(any());
    }

    @Test
    void buy_insufficientGold_throwsPlanningCommandException() {
        plan.setGold(0);
        stubHappyPath();
        when(shopService.loadShop(roundId, aliceId))
                .thenReturn(PlanningShop.of("Squire", "Mage", "Ranger"));

        assertThatThrownBy(() -> commandService.buy(gameId, 1, aliceId, key, 0))
                .isInstanceOf(PlanningCommandException.class)
                .extracting(ex -> ((PlanningCommandException) ex).getError())
                .isEqualTo(PlanningError.INSUFFICIENT_GOLD);

        verify(shopService, never()).consumeOffer(any(), any(), any(Integer.class));
    }

    @Test
    void buy_whenGameLocked_throwsRoundLockedException() {
        game.setState(GameStates.LOCKED);
        when(gameRepository.findById(gameId)).thenReturn(Optional.of(game));
        when(roundRepository.findByGameIdAndRoundNumber(gameId, 1)).thenReturn(Optional.of(round));
        when(roundPlanRepository.findByRoundIdAndPlayerId(roundId, aliceId)).thenReturn(Optional.of(plan));
        when(commandRepository.findByRoundPlanIdAndIdempotencyKey(planId, key))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> commandService.buy(gameId, 1, aliceId, key, 0))
                .isInstanceOf(RoundLockedException.class);
    }

    @Test
    void buy_wrongCurrentRound_throwsWrongGameState() {
        game.setCurrentRound(2);
        when(gameRepository.findById(gameId)).thenReturn(Optional.of(game));
        when(roundRepository.findByGameIdAndRoundNumber(gameId, 1)).thenReturn(Optional.of(round));
        when(roundPlanRepository.findByRoundIdAndPlayerId(roundId, aliceId)).thenReturn(Optional.of(plan));

        assertThatThrownBy(() -> commandService.buy(gameId, 1, aliceId, key, 0))
                .isInstanceOf(WrongGameStateException.class);
    }

    @Test
    void buy_whenPlanLocked_throwsPlanningLocked() {
        plan.setLocked(true);
        stubHappyPath();
        when(shopService.loadShop(roundId, aliceId))
                .thenReturn(PlanningShop.of("Squire", "Mage", "Ranger"));

        assertThatThrownBy(() -> commandService.buy(gameId, 1, aliceId, key, 0))
                .isInstanceOf(PlanningCommandException.class)
                .satisfies(ex -> {
                    PlanningCommandException pce = (PlanningCommandException) ex;
                    assertThat(pce.getError()).isEqualTo(PlanningError.LOCKED);
                    assertThat(pce.httpStatus()).isEqualTo(423);
                });

        verify(shopService, never()).consumeOffer(any(), any(), any(Integer.class));
    }

    @Test
    void buy_whenWaiting_throwsGameNotReady() {
        game.setState(GameStates.WAITING_FOR_PLAYERS);
        when(gameRepository.findById(gameId)).thenReturn(Optional.of(game));
        when(roundRepository.findByGameIdAndRoundNumber(gameId, 1)).thenReturn(Optional.of(round));
        when(roundPlanRepository.findByRoundIdAndPlayerId(roundId, aliceId)).thenReturn(Optional.of(plan));

        assertThatThrownBy(() -> commandService.buy(gameId, 1, aliceId, key, 0))
                .isInstanceOf(GameNotReadyException.class);
    }

    @Test
    void lock_bothPlayers_transitionsGameToLocked() {
        stubHappyPath();
        stubPersists();
        RoundPlan bobPlan = new RoundPlan(roundId, bobId, 10);
        ReflectionTestUtils.setField(bobPlan, "id", UUID.randomUUID());
        bobPlan.setLocked(true);

        when(shopService.loadShop(roundId, aliceId)).thenReturn(PlanningShop.empty());
        when(commandRepository.countByRoundPlanId(planId)).thenReturn(0);
        when(roundPlanRepository.findByRoundIdAndPlayerId(roundId, bobId))
                .thenReturn(Optional.of(bobPlan));
        doAnswer(invocation -> {
            game.setState(GameStates.LOCKED);
            round.setState(GameStates.LOCKED);
            return null;
        }).when(planningDeadlineService).maybeTransitionBothLocked(game, round);

        CommandResponse response = commandService.lock(gameId, 1, aliceId, key);

        assertThat(response.isLocked()).isTrue();
        assertThat(response.opponentIsLocked()).isTrue();
        assertThat(response.nextState()).isEqualTo(GameStates.LOCKED);
        assertThat(game.getState()).isEqualTo(GameStates.LOCKED);
        assertThat(round.getState()).isEqualTo(GameStates.LOCKED);
        verify(planningDeadlineService).maybeTransitionBothLocked(game, round);
    }

    // Pretend the DB/lookups succeed for a normal command
    private void stubHappyPath() {
        when(gameRepository.findById(gameId)).thenReturn(Optional.of(game));
        when(roundRepository.findByGameIdAndRoundNumber(gameId, 1)).thenReturn(Optional.of(round));
        when(roundPlanRepository.findByRoundIdAndPlayerId(roundId, aliceId)).thenReturn(Optional.of(plan));
        when(commandRepository.findByRoundPlanIdAndIdempotencyKey(planId, key))
                .thenReturn(Optional.empty());
    }

    // Pretend saves work normally
    private void stubPersists() {
        when(roundPlanRepository.saveAndFlush(any(RoundPlan.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(commandRepository.saveAndFlush(any(Command.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }
}
