package com.kingdom.api.service;

import com.kingdom.api.entity.Game;
import com.kingdom.api.entity.GameStates;
import com.kingdom.api.entity.Round;
import com.kingdom.api.entity.RoundPlan;
import com.kingdom.api.repository.GameRepository;
import com.kingdom.api.repository.RoundPlanRepository;
import com.kingdom.api.repository.RoundRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.FlushModeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlanningDeadlineServiceTest {

    private static final Instant NOW = Instant.parse("2024-06-01T12:00:00Z");

    @Mock
    private RoundPlanRepository roundPlanRepository;
    @Mock
    private GameRepository gameRepository;
    @Mock
    private RoundRepository roundRepository;
    @Mock
    private EntityManager entityManager;

    private PlanningDeadlineService service;

    private final UUID gameId = UUID.randomUUID();
    private final UUID roundId = UUID.randomUUID();
    private final UUID aliceId = UUID.randomUUID();
    private final UUID bobId = UUID.randomUUID();

    private Game game;
    private Round round;
    private RoundPlan alicePlan;
    private RoundPlan bobPlan;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        PlatformTransactionManager txManager = new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
            }

            @Override
            public void rollback(TransactionStatus status) {
            }
        };
        service = new PlanningDeadlineService(
                clock, roundPlanRepository, gameRepository, roundRepository, entityManager, txManager);

        // refresh is a no-op in unit tests (entities already carry the desired state).
        lenient().doAnswer(invocation -> null).when(entityManager).refresh(any());
        lenient().when(entityManager.getFlushMode()).thenReturn(FlushModeType.AUTO);

        game = Game.create(aliceId);
        ReflectionTestUtils.setField(game, "id", gameId);
        game.setPlayer2Id(bobId);
        game.setState(GameStates.PREPARATION);
        game.setCurrentRound(1);

        round = new Round(gameId, 1, GameStates.PREPARATION, NOW.plusSeconds(45));
        ReflectionTestUtils.setField(round, "id", roundId);

        alicePlan = new RoundPlan(roundId, aliceId, 10);
        ReflectionTestUtils.setField(alicePlan, "id", UUID.randomUUID());
        bobPlan = new RoundPlan(roundId, bobId, 10);
        ReflectionTestUtils.setField(bobPlan, "id", UUID.randomUUID());
    }

    @Test
    void enforce_beforeDeadline_returnsFalseAndDoesNotLock() {
        boolean past = service.enforceDeadlineOrAutoLock(game, round);

        assertThat(past).isFalse();
        assertThat(game.getState()).isEqualTo(GameStates.PREPARATION);
        verify(roundPlanRepository, never()).findByRoundId(roundId);
        verify(gameRepository, never()).findById(any());
        verify(roundRepository, never()).lockRoundForUpdate(any());
    }

    @Test
    void enforce_atDeadline_locksRoundThenGameBeforeReadingPlans() {
        round.setPlanningDeadline(NOW);
        when(gameRepository.existsById(gameId)).thenReturn(true);
        when(roundRepository.existsById(roundId)).thenReturn(true);
        when(roundRepository.lockRoundForUpdate(roundId)).thenReturn(Optional.of(round));
        when(gameRepository.lockGameForUpdate(gameId)).thenReturn(Optional.of(game));
        when(roundPlanRepository.findByRoundId(roundId)).thenReturn(List.of(alicePlan, bobPlan));

        boolean past = service.enforceDeadlineOrAutoLock(game, round);

        assertThat(past).isTrue();
        assertThat(alicePlan.isLocked()).isTrue();
        assertThat(bobPlan.isLocked()).isTrue();
        assertThat(alicePlan.getLockedAt()).isEqualTo(NOW);
        assertThat(bobPlan.getLockedAt()).isEqualTo(NOW);
        assertThat(game.getState()).isEqualTo(GameStates.LOCKED);
        assertThat(round.getState()).isEqualTo(GameStates.LOCKED);
        verify(gameRepository).save(game);
        verify(roundRepository).save(round);
        verify(roundPlanRepository).save(alicePlan);
        verify(roundPlanRepository).save(bobPlan);

        InOrder order = inOrder(roundRepository, gameRepository, roundPlanRepository);
        order.verify(roundRepository).lockRoundForUpdate(roundId);
        order.verify(gameRepository).lockGameForUpdate(gameId);
        order.verify(roundPlanRepository).findByRoundId(roundId);
    }

    @Test
    void autoLockIfDeadlinePassed_whenExpired_loadsRoundAndLocks() {
        round.setPlanningDeadline(NOW);
        when(gameRepository.findById(gameId)).thenReturn(Optional.of(game));
        when(roundRepository.findByGameIdAndRoundNumber(gameId, 1)).thenReturn(Optional.of(round));
        when(roundRepository.lockRoundForUpdate(roundId)).thenReturn(Optional.of(round));
        when(gameRepository.lockGameForUpdate(gameId)).thenReturn(Optional.of(game));
        when(roundPlanRepository.findByRoundId(roundId)).thenReturn(List.of(alicePlan, bobPlan));

        service.autoLockIfDeadlinePassed(gameId);

        assertThat(game.getState()).isEqualTo(GameStates.LOCKED);
        verify(gameRepository).findById(gameId);
        verify(roundRepository).findByGameIdAndRoundNumber(gameId, 1);
        InOrder order = inOrder(roundRepository, gameRepository, roundPlanRepository);
        order.verify(roundRepository).lockRoundForUpdate(roundId);
        order.verify(gameRepository).lockGameForUpdate(gameId);
        order.verify(roundPlanRepository).findByRoundId(roundId);
    }

    @Test
    void autoLockIfDeadlinePassed_oneAlreadyLocked_locksOtherAndTransitions() {
        Instant earlier = NOW.minusSeconds(10);
        alicePlan.setLocked(true);
        alicePlan.setLockedAt(earlier);
        round.setPlanningDeadline(NOW.minusSeconds(1));
        when(gameRepository.findById(gameId)).thenReturn(Optional.of(game));
        when(roundRepository.findByGameIdAndRoundNumber(gameId, 1)).thenReturn(Optional.of(round));
        when(roundRepository.lockRoundForUpdate(roundId)).thenReturn(Optional.of(round));
        when(gameRepository.lockGameForUpdate(gameId)).thenReturn(Optional.of(game));
        when(roundPlanRepository.findByRoundId(roundId)).thenReturn(List.of(alicePlan, bobPlan));

        service.autoLockIfDeadlinePassed(gameId);

        assertThat(alicePlan.getLockedAt()).isEqualTo(earlier);
        assertThat(bobPlan.isLocked()).isTrue();
        assertThat(bobPlan.getLockedAt()).isEqualTo(NOW);
        assertThat(game.getState()).isEqualTo(GameStates.LOCKED);
        verify(roundPlanRepository, never()).save(alicePlan);
        verify(roundPlanRepository).save(bobPlan);
    }

    @Test
    void autoLockIfDeadlinePassed_beforeDeadline_isNoOp() {
        when(gameRepository.findById(gameId)).thenReturn(Optional.of(game));
        when(roundRepository.findByGameIdAndRoundNumber(gameId, 1)).thenReturn(Optional.of(round));

        service.autoLockIfDeadlinePassed(gameId);

        assertThat(game.getState()).isEqualTo(GameStates.PREPARATION);
        verify(roundPlanRepository, never()).findByRoundId(roundId);
        verify(roundRepository, never()).lockRoundForUpdate(any());
    }

    @Test
    void autoLockIfDeadlinePassed_whenAlreadyLocked_isNoOp() {
        game.setState(GameStates.LOCKED);
        when(gameRepository.findById(gameId)).thenReturn(Optional.of(game));

        service.autoLockIfDeadlinePassed(gameId);

        verify(roundRepository, never()).findByGameIdAndRoundNumber(gameId, 1);
        verify(roundPlanRepository, never()).findByRoundId(roundId);
    }

    @Test
    void autoLockExpiredRound_whenLockShowsResolving_doesNotOverwrite() {
        round.setPlanningDeadline(NOW);
        Game resolvingGame = Game.create(aliceId);
        ReflectionTestUtils.setField(resolvingGame, "id", gameId);
        resolvingGame.setState(GameStates.RESOLVING);
        Round resolvingRound = new Round(gameId, 1, GameStates.RESOLVING, NOW);
        ReflectionTestUtils.setField(resolvingRound, "id", roundId);

        when(gameRepository.existsById(gameId)).thenReturn(true);
        when(roundRepository.existsById(roundId)).thenReturn(true);
        when(roundRepository.lockRoundForUpdate(roundId)).thenReturn(Optional.of(resolvingRound));
        when(gameRepository.lockGameForUpdate(gameId)).thenReturn(Optional.of(resolvingGame));

        boolean past = service.enforceDeadlineOrAutoLock(game, round);

        assertThat(past).isTrue();
        assertThat(resolvingGame.getState()).isEqualTo(GameStates.RESOLVING);
        assertThat(resolvingRound.getState()).isEqualTo(GameStates.RESOLVING);
        assertThat(game.getState()).isEqualTo(GameStates.PREPARATION);
        assertThat(round.getState()).isEqualTo(GameStates.PREPARATION);
        verify(roundPlanRepository, never()).findByRoundId(any());
        verify(gameRepository, never()).save(any());
        verify(roundRepository, never()).save(any());
    }

    @Test
    void maybeTransitionBothLocked_whenOneUnlocked_doesNotTransition() {
        alicePlan.setLocked(true);
        bobPlan.setLocked(false);
        when(roundRepository.lockRoundForUpdate(roundId)).thenReturn(Optional.of(round));
        when(gameRepository.lockGameForUpdate(gameId)).thenReturn(Optional.of(game));
        when(roundPlanRepository.findByRoundId(roundId)).thenReturn(List.of(alicePlan, bobPlan));

        service.maybeTransitionBothLocked(game, round);

        assertThat(game.getState()).isEqualTo(GameStates.PREPARATION);
        verify(gameRepository, never()).save(game);
        InOrder order = inOrder(roundRepository, gameRepository, roundPlanRepository);
        order.verify(roundRepository).lockRoundForUpdate(roundId);
        order.verify(gameRepository).lockGameForUpdate(gameId);
        order.verify(roundPlanRepository).findByRoundId(roundId);
    }

    @Test
    void maybeTransitionBothLocked_whenNotExactlyTwoPlans_doesNotTransition() {
        alicePlan.setLocked(true);
        when(roundRepository.lockRoundForUpdate(roundId)).thenReturn(Optional.of(round));
        when(gameRepository.lockGameForUpdate(gameId)).thenReturn(Optional.of(game));
        when(roundPlanRepository.findByRoundId(roundId)).thenReturn(List.of(alicePlan));

        service.maybeTransitionBothLocked(game, round);

        assertThat(game.getState()).isEqualTo(GameStates.PREPARATION);
        verify(gameRepository, never()).save(any());
    }

    @Test
    void maybeTransitionBothLocked_whenAlreadyLocked_doesNotTransition() {
        game.setState(GameStates.LOCKED);
        round.setState(GameStates.LOCKED);
        when(roundRepository.lockRoundForUpdate(roundId)).thenReturn(Optional.of(round));
        when(gameRepository.lockGameForUpdate(gameId)).thenReturn(Optional.of(game));
        when(roundPlanRepository.findByRoundId(roundId)).thenReturn(List.of(alicePlan, bobPlan));

        service.maybeTransitionBothLocked(game, round);

        verify(gameRepository, never()).save(any());
        verify(roundRepository, never()).save(any());
    }

    @Test
    void maybeTransitionBothLocked_whenBothLocked_transitions() {
        alicePlan.setLocked(true);
        bobPlan.setLocked(true);
        when(roundRepository.lockRoundForUpdate(roundId)).thenReturn(Optional.of(round));
        when(gameRepository.lockGameForUpdate(gameId)).thenReturn(Optional.of(game));
        when(roundPlanRepository.findByRoundId(roundId)).thenReturn(List.of(alicePlan, bobPlan));

        service.maybeTransitionBothLocked(game, round);

        assertThat(game.getState()).isEqualTo(GameStates.LOCKED);
        assertThat(round.getState()).isEqualTo(GameStates.LOCKED);
        verify(gameRepository).save(game);
        verify(roundRepository).save(round);
    }

    @Test
    void maybeTransitionBothLocked_whenLockShowsResolving_doesNotOverwrite() {
        alicePlan.setLocked(true);
        bobPlan.setLocked(true);
        // Caller still thinks PREPARATION; locked rows already moved on (worker claim).
        Round resolvingRound = new Round(gameId, 1, GameStates.RESOLVING, NOW);
        ReflectionTestUtils.setField(resolvingRound, "id", roundId);
        Game resolvingGame = Game.create(aliceId);
        ReflectionTestUtils.setField(resolvingGame, "id", gameId);
        resolvingGame.setState(GameStates.RESOLVING);

        when(roundRepository.lockRoundForUpdate(roundId)).thenReturn(Optional.of(resolvingRound));
        when(gameRepository.lockGameForUpdate(gameId)).thenReturn(Optional.of(resolvingGame));
        when(roundPlanRepository.findByRoundId(roundId)).thenReturn(List.of(alicePlan, bobPlan));

        service.maybeTransitionBothLocked(game, round);

        assertThat(resolvingGame.getState()).isEqualTo(GameStates.RESOLVING);
        assertThat(resolvingRound.getState()).isEqualTo(GameStates.RESOLVING);
        // Caller instances stay PREPARATION — distinct from the locked rows in this unit test.
        assertThat(game.getState()).isEqualTo(GameStates.PREPARATION);
        verify(gameRepository, never()).save(any());
        verify(roundRepository, never()).save(any());
        verify(entityManager).refresh(resolvingRound);
        verify(entityManager).refresh(resolvingGame);
    }
}
