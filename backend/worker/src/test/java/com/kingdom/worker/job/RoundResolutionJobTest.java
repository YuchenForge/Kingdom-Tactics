package com.kingdom.worker.job;

import com.kingdom.api.entity.GameStates;
import com.kingdom.api.entity.Round;
import com.kingdom.api.repository.RoundRepository;
import com.kingdom.engine.domain.Board;
import com.kingdom.engine.domain.CombatBoard;
import com.kingdom.engine.domain.ResolutionResult;
import com.kingdom.worker.service.ClaimService;
import com.kingdom.worker.service.ClaimedRound;
import com.kingdom.worker.service.MatchAdvancementService;
import com.kingdom.worker.service.ResolutionService;
import com.kingdom.worker.service.ResolveService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoundResolutionJobTest {

    @Mock
    private ClaimService claimService;
    @Mock
    private ResolutionService resolutionService;
    @Mock
    private ResolveService resolveService;
    @Mock
    private MatchAdvancementService advancementService;
    @Mock
    private RoundRepository roundRepository;

    private RoundResolutionJob job;

    @BeforeEach
    void setUp() {
        job = new RoundResolutionJob(
                claimService, resolutionService, resolveService, advancementService, roundRepository);
    }

    @Test
    void processLocked_whenClaimEmpty_returnsWithoutFurtherWork() {
        UUID roundId = UUID.randomUUID();
        when(claimService.claim(roundId)).thenReturn(Optional.empty());

        job.processLocked(roundId);

        verify(claimService).claim(roundId);
        verifyNoInteractions(resolutionService, resolveService, advancementService, roundRepository);
        verifyNoMoreInteractions(claimService);
    }

    @Test
    void processLocked_claimsThenResolvesThenCommitsThenAdvances() {
        UUID roundId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        long seed = 99L;
        ClaimedRound claimed = new ClaimedRound(roundId, gameId, 1, seed);
        ResolutionResult result = stubResult();
        when(claimService.claim(roundId)).thenReturn(Optional.of(claimed));
        when(resolutionService.resolve(roundId, gameId, seed)).thenReturn(result);
        when(resolveService.commit(roundId, gameId, 1, result)).thenReturn(true);
        when(advancementService.advance(roundId)).thenReturn(true);

        job.processLocked(roundId);

        InOrder order = inOrder(claimService, resolutionService, resolveService, advancementService);
        order.verify(claimService).claim(roundId);
        order.verify(resolutionService).resolve(roundId, gameId, seed);
        order.verify(resolveService).commit(roundId, gameId, 1, result);
        order.verify(advancementService).advance(roundId);
        verifyNoInteractions(roundRepository);
        verifyNoMoreInteractions(claimService, resolutionService, resolveService, advancementService);
    }

    @Test
    void processLocked_whenEngineFails_doesNotCommitOrAdvance() {
        UUID roundId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        long seed = 7L;
        when(claimService.claim(roundId))
                .thenReturn(Optional.of(new ClaimedRound(roundId, gameId, 1, seed)));
        when(resolutionService.resolve(roundId, gameId, seed))
                .thenThrow(new IllegalStateException("boom"));

        job.processLocked(roundId);

        verify(resolutionService).resolve(roundId, gameId, seed);
        verifyNoInteractions(resolveService, advancementService);
        verifyNoMoreInteractions(claimService, resolutionService);
    }

    @Test
    void processLocked_whenTx2NoOps_doesNotAdvance() {
        UUID roundId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        long seed = 3L;
        ResolutionResult result = stubResult();
        when(claimService.claim(roundId))
                .thenReturn(Optional.of(new ClaimedRound(roundId, gameId, 1, seed)));
        when(resolutionService.resolve(roundId, gameId, seed)).thenReturn(result);
        when(resolveService.commit(roundId, gameId, 1, result)).thenReturn(false);

        job.processLocked(roundId);

        verify(resolveService).commit(roundId, gameId, 1, result);
        verifyNoInteractions(advancementService);
    }

    @Test
    void retryResolving_usesExistingSeedWithoutClaimThenCommitsThenAdvances() {
        UUID roundId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        long seed = 42L;
        Round round = resolvingRound(roundId, gameId, 2, seed);
        ResolutionResult result = stubResult();
        when(roundRepository.findById(roundId)).thenReturn(Optional.of(round));
        when(resolutionService.resolve(roundId, gameId, seed)).thenReturn(result);
        when(resolveService.commit(eq(roundId), eq(gameId), eq(2), eq(result))).thenReturn(true);
        when(advancementService.advance(roundId)).thenReturn(true);

        job.retryResolving(roundId);

        InOrder order = inOrder(resolutionService, resolveService, advancementService);
        order.verify(resolutionService).resolve(roundId, gameId, seed);
        order.verify(resolveService).commit(roundId, gameId, 2, result);
        order.verify(advancementService).advance(roundId);
        verifyNoInteractions(claimService);
        verify(roundRepository, never()).save(any());
    }

    @Test
    void retryResolving_whenNotResolving_skipsEngine() {
        UUID roundId = UUID.randomUUID();
        Round round = resolvingRound(roundId, UUID.randomUUID(), 1, 1L);
        round.setState(GameStates.ROUND_RESULT);
        when(roundRepository.findById(roundId)).thenReturn(Optional.of(round));

        job.retryResolving(roundId);

        verifyNoInteractions(claimService, resolutionService, resolveService, advancementService);
    }

    @Test
    void retryResolving_whenSeedMissing_skipsEngine() {
        UUID roundId = UUID.randomUUID();
        Round round = resolvingRound(roundId, UUID.randomUUID(), 1, null);
        when(roundRepository.findById(roundId)).thenReturn(Optional.of(round));

        job.retryResolving(roundId);

        verifyNoInteractions(claimService, resolutionService, resolveService, advancementService);
    }

    @Test
    void advanceOnly_delegatesToAdvancementService() {
        UUID roundId = UUID.randomUUID();
        when(advancementService.advance(roundId)).thenReturn(true);

        job.advanceOnly(roundId);

        verify(advancementService).advance(roundId);
        verifyNoInteractions(claimService, resolutionService, resolveService, roundRepository);
    }

    private static Round resolvingRound(UUID roundId, UUID gameId, int roundNumber, Long seed) {
        Round round = new Round(gameId, roundNumber, GameStates.RESOLVING, Instant.parse("2024-06-01T12:00:45Z"));
        ReflectionTestUtils.setField(round, "id", roundId);
        round.setCombatSeed(seed);
        return round;
    }

    private static ResolutionResult stubResult() {
        CombatBoard empty = CombatBoard.merge(new Board(0), new Board(1));
        return new ResolutionResult(List.of(), empty, new int[] {0, 0}, 0, "DRAW", -1);
    }
}
