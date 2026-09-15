package com.kingdom.api.service;

import com.kingdom.api.entity.Game;
import com.kingdom.api.entity.GameStates;
import com.kingdom.api.entity.Round;
import com.kingdom.api.entity.RoundPlan;
import com.kingdom.api.repository.GameRepository;
import com.kingdom.api.repository.RoundPlanRepository;
import com.kingdom.api.repository.RoundRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
        service = new PlanningDeadlineService(
                clock, roundPlanRepository, gameRepository, roundRepository);

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
    }

    @Test
    void enforce_atDeadline_autoLocksBothPlansAndTransitions() {
        round.setPlanningDeadline(NOW);
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
        verify(roundPlanRepository, times(2)).findByRoundId(roundId);
    }

    @Test
    void autoLockIfDeadlinePassed_whenExpired_loadsRoundAndLocks() {
        // Thin entry-point check: load game/round then same lock path as enforce_atDeadline.
        round.setPlanningDeadline(NOW);
        when(gameRepository.findById(gameId)).thenReturn(Optional.of(game));
        when(roundRepository.findByGameIdAndRoundNumber(gameId, 1)).thenReturn(Optional.of(round));
        when(roundPlanRepository.findByRoundId(roundId)).thenReturn(List.of(alicePlan, bobPlan));

        service.autoLockIfDeadlinePassed(gameId);

        assertThat(game.getState()).isEqualTo(GameStates.LOCKED);
        verify(gameRepository).findById(gameId);
        verify(roundRepository).findByGameIdAndRoundNumber(gameId, 1);
    }

    @Test
    void autoLockIfDeadlinePassed_oneAlreadyLocked_locksOtherAndTransitions() {
        Instant earlier = NOW.minusSeconds(10);
        alicePlan.setLocked(true);
        alicePlan.setLockedAt(earlier);
        round.setPlanningDeadline(NOW.minusSeconds(1));
        when(gameRepository.findById(gameId)).thenReturn(Optional.of(game));
        when(roundRepository.findByGameIdAndRoundNumber(gameId, 1)).thenReturn(Optional.of(round));
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
    void maybeTransitionBothLocked_whenOneUnlocked_doesNotTransition() {
        alicePlan.setLocked(true);
        bobPlan.setLocked(false);
        when(roundPlanRepository.findByRoundId(roundId)).thenReturn(List.of(alicePlan, bobPlan));

        service.maybeTransitionBothLocked(game, round);

        assertThat(game.getState()).isEqualTo(GameStates.PREPARATION);
        verify(gameRepository, never()).save(game);
    }

    @Test
    void maybeTransitionBothLocked_whenBothLocked_transitions() {
        alicePlan.setLocked(true);
        bobPlan.setLocked(true);
        when(roundPlanRepository.findByRoundId(roundId)).thenReturn(List.of(alicePlan, bobPlan));

        service.maybeTransitionBothLocked(game, round);

        assertThat(game.getState()).isEqualTo(GameStates.LOCKED);
        assertThat(round.getState()).isEqualTo(GameStates.LOCKED);
        verify(gameRepository).save(game);
        verify(roundRepository).save(round);
    }
}
