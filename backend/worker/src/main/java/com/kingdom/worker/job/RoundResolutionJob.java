package com.kingdom.worker.job;

import com.kingdom.worker.service.ClaimService;
import com.kingdom.worker.service.ClaimedRound;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * Orchestrates path 1–3. TX boundaries live on collaborator services
 * (e.g. ClaimService); this class must not wrap engine work in a TX.
 */
@Service
public class RoundResolutionJob {

    private static final Logger log = LoggerFactory.getLogger(RoundResolutionJob.class);

    private final ClaimService claimService;

    public RoundResolutionJob(ClaimService claimService) {
        this.claimService = claimService;
    }

    /** Path 1: claim (TX1) → engine → resolve (TX2) → advance (TX3). */
    public void processLocked(UUID roundId) {
        Optional<ClaimedRound> claimed = claimService.claim(roundId);
        if (claimed.isEmpty()) {
            return; // lost SKIP LOCKED race or stale scan
        }
        ClaimedRound c = claimed.get();
        log.debug("Claimed roundId={} gameId={} round={} seed={} (engine/TX2/TX3 not wired yet)",
                c.roundId(), c.gameId(), c.roundNumber(), c.combatSeed());
    }

    /** Path 2: engine with existing combat_seed → resolve (TX2) → advance (TX3). */
    public void retryResolving(UUID roundId) {
        log.debug("retryResolving stub roundId={}", roundId);
    }

    /** Path 3: advance (TX3) only. */
    public void advanceOnly(UUID roundId) {
        log.debug("advanceOnly stub roundId={}", roundId);
    }
}
