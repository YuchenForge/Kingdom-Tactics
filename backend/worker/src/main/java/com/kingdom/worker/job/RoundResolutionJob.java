package com.kingdom.worker.job;

import com.kingdom.api.entity.GameStates;
import com.kingdom.api.entity.Round;
import com.kingdom.api.repository.RoundRepository;
import com.kingdom.engine.domain.ResolutionResult;
import com.kingdom.worker.service.ClaimService;
import com.kingdom.worker.service.ClaimedRound;
import com.kingdom.worker.service.MatchAdvancementService;
import com.kingdom.worker.service.ResolutionService;
import com.kingdom.worker.service.ResolveService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * Orchestrates path 1–3. TX boundaries live on collaborator services
 * (ClaimService / ResolveService / MatchAdvancementService); this class must not
 * wrap engine work in a TX.
 *
 * Sequence for paths 1–2: engine (no TX) → resolveService.commit (TX2) → advance (TX3).
 * Path 3: advance only (recovers if worker died between TX2 and TX3).
 */
@Service
public class RoundResolutionJob {

    private static final Logger log = LoggerFactory.getLogger(RoundResolutionJob.class);

    private final ClaimService claimService;
    private final ResolutionService resolutionService;
    private final ResolveService resolveService;
    private final MatchAdvancementService advancementService;
    private final RoundRepository roundRepository;

    public RoundResolutionJob(
            ClaimService claimService,
            ResolutionService resolutionService,
            ResolveService resolveService,
            MatchAdvancementService advancementService,
            RoundRepository roundRepository) {
        this.claimService = claimService;
        this.resolutionService = resolutionService;
        this.resolveService = resolveService;
        this.advancementService = advancementService;
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

    /** Path 3: advance (TX3) only — recover after TX2 if advanced_at still null. */
    public void advanceOnly(UUID roundId) {
        boolean advanced = advancementService.advance(roundId);
        if (!advanced) {
            log.debug("TX3 no-op roundId={}", roundId);
        }
    }

    /**
     * Engine outside TX; on failure leave RESOLVING.
     * TX2 then TX3 are separate bean transactions (no outer @Transactional here).
     */
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

        log.debug("TX2 committed roundId={} gameId={} round={} seed={} events={} endReason={}",
                roundId, gameId, roundNumber, combatSeed,
                result.getEvents().size(), result.getEndReason());

        // New TX via MatchAdvancementService bean — path 3 recovers if we die here
        advancementService.advance(roundId);
    }
}
