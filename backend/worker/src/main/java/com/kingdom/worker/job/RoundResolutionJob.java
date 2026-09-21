package com.kingdom.worker.job;

import com.kingdom.api.entity.GameStates;
import com.kingdom.api.entity.Round;
import com.kingdom.api.repository.RoundRepository;
import com.kingdom.engine.domain.ResolutionResult;
import com.kingdom.worker.service.ClaimService;
import com.kingdom.worker.service.ClaimedRound;
import com.kingdom.worker.service.ResolutionService;
import com.kingdom.worker.service.ResolveService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * Orchestrates path 1–3. TX boundaries live on collaborator services
 * (e.g. ClaimService / ResolveService); this class must not wrap engine work in a TX.
 */
@Service
public class RoundResolutionJob {

    private static final Logger log = LoggerFactory.getLogger(RoundResolutionJob.class);

    private final ClaimService claimService;
    private final ResolutionService resolutionService;
    private final ResolveService resolveService;
    private final RoundRepository roundRepository;

    public RoundResolutionJob(
            ClaimService claimService,
            ResolutionService resolutionService,
            ResolveService resolveService,
            RoundRepository roundRepository) {
        this.claimService = claimService;
        this.resolutionService = resolutionService;
        this.resolveService = resolveService;
        this.roundRepository = roundRepository;
    }

    /** Path 1: claim (TX1) → engine → resolve (TX2) → advance (TX3). */
    public void processLocked(UUID roundId) {
        Optional<ClaimedRound> claimed = claimService.claim(roundId);
        if (claimed.isEmpty()) {
            return; // lost SKIP LOCKED race or stale scan
        }
        ClaimedRound c = claimed.get();
        runEngineThenCommit(c.roundId(), c.gameId(), c.roundNumber(), c.combatSeed());
    }

    /**
     * Path 2: engine with existing combat_seed → resolve (TX2) → advance (TX3).
     * Does not claim or change combat_seed (no TX1).
     */
    public void retryResolving(UUID roundId) {
        Round round = roundRepository.findById(roundId).orElse(null);
        if (round == null) {
            log.warn("retryResolving: round missing roundId={}", roundId);
            return;
        }
        if (!GameStates.RESOLVING.equals(round.getState())) {
            return; // stale scan / already advanced
        }
        if (round.getCombatSeed() == null) {
            log.warn("retryResolving: missing combat_seed; leaving RESOLVING roundId={}", roundId);
            return;
        }
        runEngineThenCommit(round.getId(), round.getGameId(), round.getRoundNumber(), round.getCombatSeed());
    }

    /** Path 3: advance (TX3) only. */
    public void advanceOnly(UUID roundId) {
        log.debug("advanceOnly stub roundId={}", roundId);
    }

    /** Engine outside TX; on failure leave RESOLVING. TX2 only after success. */
    private void runEngineThenCommit(UUID roundId, UUID gameId, int roundNumber, long combatSeed) {
        ResolutionResult result;
        try {
            result = resolutionService.resolve(roundId, gameId, combatSeed);
        } catch (RuntimeException ex) {
            log.warn("Engine failed; leaving RESOLVING round={}", roundId, ex);
            return;
        }

        boolean committed = resolveService.commit(roundId, gameId, roundNumber, result);
        if (!committed) {
            log.debug("TX2 no-op roundId={} (already ROUND_RESULT or lost race)", roundId);
            return;
        }
        // TX3 later: advancementService.advance(roundId)
        log.debug("TX2 committed roundId={} gameId={} round={} seed={} events={} endReason={}",
                roundId, gameId, roundNumber, combatSeed,
                result.getEvents().size(), result.getEndReason());
    }
}
