package com.kingdom.worker.job;

import com.kingdom.worker.service.ClaimService;
import com.kingdom.worker.service.ClaimedRound;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoundResolutionJobTest {

    @Mock
    private ClaimService claimService;

    private RoundResolutionJob job;

    @BeforeEach
    void setUp() {
        job = new RoundResolutionJob(claimService);
    }

    @Test
    void processLocked_whenClaimEmpty_returnsWithoutFurtherWork() {
        UUID roundId = UUID.randomUUID();
        when(claimService.claim(roundId)).thenReturn(Optional.empty());

        job.processLocked(roundId);

        verify(claimService).claim(roundId);
        verifyNoMoreInteractions(claimService);
    }

    @Test
    void processLocked_invokesClaimBeforeAnyLaterWork() {
        UUID roundId = UUID.randomUUID();
        ClaimedRound claimed = new ClaimedRound(roundId, UUID.randomUUID(), 1, 99L);
        when(claimService.claim(roundId)).thenReturn(Optional.of(claimed));

        job.processLocked(roundId);

        InOrder order = inOrder(claimService);
        order.verify(claimService).claim(roundId);
        // Engine / TX2 / TX3 not wired yet — claim is the only collaborator call.
        verifyNoMoreInteractions(claimService);
    }
}
