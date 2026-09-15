package com.kingdom.api.service;

import com.kingdom.api.entity.GameStates;
import com.kingdom.api.entity.Round;
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
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlanningDeadlineMonitorTest {

    private static final Instant NOW = Instant.parse("2024-06-01T12:00:00Z");

    @Mock
    private RoundRepository roundRepository;
    @Mock
    private PlanningDeadlineService planningDeadlineService;

    private PlanningDeadlineMonitor monitor;

    private final UUID gameA = UUID.randomUUID();
    private final UUID gameB = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        monitor = new PlanningDeadlineMonitor(
                Clock.fixed(NOW, ZoneOffset.UTC),
                roundRepository,
                planningDeadlineService);
    }

    @Test
    void sweep_callsAutoLockForEachExpiredGame() {
        Round roundA = round(gameA, NOW.minusSeconds(1));
        Round roundB = round(gameB, NOW);
        when(roundRepository.findByStateAndPlanningDeadlineLessThanEqual(
                GameStates.PREPARATION, NOW))
                .thenReturn(List.of(roundA, roundB));

        monitor.sweepExpiredPlanningRounds();

        verify(planningDeadlineService).autoLockIfDeadlinePassed(gameA);
        verify(planningDeadlineService).autoLockIfDeadlinePassed(gameB);
    }

    @Test
    void sweep_dedupesSameGameId() {
        Round first = round(gameA, NOW.minusSeconds(5));
        Round second = round(gameA, NOW.minusSeconds(1));
        when(roundRepository.findByStateAndPlanningDeadlineLessThanEqual(
                GameStates.PREPARATION, NOW))
                .thenReturn(List.of(first, second));

        monitor.sweepExpiredPlanningRounds();

        verify(planningDeadlineService).autoLockIfDeadlinePassed(gameA);
    }

    @Test
    void sweep_whenNoneExpired_doesNothing() {
        when(roundRepository.findByStateAndPlanningDeadlineLessThanEqual(
                GameStates.PREPARATION, NOW))
                .thenReturn(List.of());

        monitor.sweepExpiredPlanningRounds();

        verify(planningDeadlineService, never()).autoLockIfDeadlinePassed(any());
    }

    @Test
    void sweep_continuesAfterOneFailure() {
        Round roundA = round(gameA, NOW.minusSeconds(1));
        Round roundB = round(gameB, NOW);
        when(roundRepository.findByStateAndPlanningDeadlineLessThanEqual(
                GameStates.PREPARATION, NOW))
                .thenReturn(List.of(roundA, roundB));
        doThrow(new RuntimeException("boom"))
                .when(planningDeadlineService).autoLockIfDeadlinePassed(gameA);

        monitor.sweepExpiredPlanningRounds();

        verify(planningDeadlineService).autoLockIfDeadlinePassed(gameA);
        verify(planningDeadlineService).autoLockIfDeadlinePassed(gameB);
    }

    private static Round round(UUID gameId, Instant deadline) {
        Round round = new Round(gameId, 1, GameStates.PREPARATION, deadline);
        ReflectionTestUtils.setField(round, "id", UUID.randomUUID());
        return round;
    }
}
