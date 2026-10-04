package com.kingdom.worker.job;

import com.kingdom.api.entity.GameStates;
import com.kingdom.api.repository.RoundRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

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
class RoundScannerTest {

    private static final Instant NOW = Instant.parse("2026-10-03T12:00:00Z");
    private static final Pageable PAGE = PageRequest.of(0, 50);

    @Mock
    private RoundRepository rounds;
    @Mock
    private RoundResolutionJob job;

    private RoundScanner scanner;

    @BeforeEach
    void setUp() {
        scanner = new RoundScanner(rounds, job, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void poll_whenEmpty_neverCallsJob() {
        stubEmptyFinders();

        scanner.poll();

        verify(job, never()).processLocked(any());
        verify(job, never()).retryResolving(any());
        verify(job, never()).advanceOnly(any());
    }

    @Test
    void poll_dispatchesEachPathOnce() {
        UUID lockedId = UUID.randomUUID();
        UUID resolvingId = UUID.randomUUID();
        UUID roundResultId = UUID.randomUUID();
        when(rounds.findIdsByState(GameStates.LOCKED, PAGE)).thenReturn(List.of(lockedId));
        when(rounds.findIdsByState(GameStates.RESOLVING, PAGE)).thenReturn(List.of(resolvingId));
        when(rounds.findIdsNeedingAdvance(GameStates.ROUND_RESULT, NOW, PAGE))
                .thenReturn(List.of(roundResultId));

        scanner.poll();

        verify(job).processLocked(lockedId);
        verify(job).retryResolving(resolvingId);
        verify(job).advanceOnly(roundResultId);
    }

    @Test
    void poll_continuesAfterJobFailure() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(rounds.findIdsByState(GameStates.LOCKED, PAGE)).thenReturn(List.of(first, second));
        when(rounds.findIdsByState(GameStates.RESOLVING, PAGE)).thenReturn(List.of());
        when(rounds.findIdsNeedingAdvance(GameStates.ROUND_RESULT, NOW, PAGE)).thenReturn(List.of());
        doThrow(new RuntimeException("boom")).when(job).processLocked(first);

        scanner.poll();

        verify(job).processLocked(first);
        verify(job).processLocked(second);
    }

    private void stubEmptyFinders() {
        when(rounds.findIdsByState(any(), any(Pageable.class))).thenReturn(List.of());
        when(rounds.findIdsNeedingAdvance(any(), any(Instant.class), any(Pageable.class))).thenReturn(List.of());
    }
}
