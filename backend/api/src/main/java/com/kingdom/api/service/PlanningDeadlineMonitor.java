package com.kingdom.api.service;

import com.kingdom.api.entity.GameStates;
import com.kingdom.api.entity.Round;
import com.kingdom.api.repository.RoundRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Background safety net: every 10s, auto-lock PREPARATION rounds whose deadline has passed.
 * Active clients also finalize via opportunistic checks on commands/GET.
 */
@Component
@ConditionalOnProperty(prefix = "app.scheduling", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PlanningDeadlineMonitor {

    private static final Logger log = LoggerFactory.getLogger(PlanningDeadlineMonitor.class);

    private final Clock clock;
    private final RoundRepository roundRepository;
    private final PlanningDeadlineService planningDeadlineService;

    public PlanningDeadlineMonitor(
            Clock clock,
            RoundRepository roundRepository,
            PlanningDeadlineService planningDeadlineService) {
        this.clock = clock;
        this.roundRepository = roundRepository;
        this.planningDeadlineService = planningDeadlineService;
    }

    /**
     * Sweep expired planning rounds every 10 seconds.
     */
    @Scheduled(fixedDelayString = "${app.scheduling.planning-deadline-ms:10000}")
    public void sweepExpiredPlanningRounds() {
        Instant now = clock.instant();
        List<Round> expired = roundRepository.findByStateAndPlanningDeadlineLessThanEqual(
                GameStates.PREPARATION, now);
        if (expired.isEmpty()) {
            return;
        }

        Set<UUID> seenGames = new HashSet<>();
        int attempted = 0;
        for (Round round : expired) {
            UUID gameId = round.getGameId();
            if (!seenGames.add(gameId)) {
                continue;
            }
            attempted++;
            try {
                planningDeadlineService.autoLockIfDeadlinePassed(gameId);
            } catch (RuntimeException ex) {
                log.warn("Deadline sweep failed for game={}: {}", gameId, ex.getMessage());
            }
        }
        log.debug("Deadline sweep: candidates={} games={}", expired.size(), attempted);
    }
}
