package com.kingdom.worker.job;

import com.kingdom.api.entity.GameStates;
import com.kingdom.api.repository.RoundRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Discovers candidate rounds and dispatches work to RoundResolutionJob.
 * Discovery is read-only; claiming / resolve / advance own their transactions.
 * Uses fixedDelay so a slow tick does not overlap the next on the default single scheduler thread.
 */
@Component
@ConditionalOnProperty(prefix = "app.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RoundScanner {

    private static final Logger log = LoggerFactory.getLogger(RoundScanner.class);

    private static final int BATCH_SIZE = 50;

    private final RoundRepository rounds;
    private final RoundResolutionJob job;

    public RoundScanner(RoundRepository rounds, RoundResolutionJob job) {
        this.rounds = rounds;
        this.job = job;
    }

    @Scheduled(fixedDelayString = "${app.worker.poll-ms:10000}")
    public void poll() {
        var page = PageRequest.of(0, BATCH_SIZE);

        processPath("LOCKED",
                rounds.findIdsByState(GameStates.LOCKED, page),
                job::processLocked);
        processPath("RESOLVING",
                rounds.findIdsByState(GameStates.RESOLVING, page),
                job::retryResolving);
        processPath("ADVANCE",
                rounds.findIdsNeedingAdvance(GameStates.ROUND_RESULT, page),
                job::advanceOnly);
    }

    private void processPath(String label, List<UUID> candidates, Consumer<UUID> action) {
        if (candidates.isEmpty()) {
            return;
        }
        int ok = 0;
        for (UUID id : candidates) {
            try {
                action.accept(id);
                ok++;
            } catch (RuntimeException ex) {
                log.warn("Worker {} failed round={}: {}", label, id, ex.getMessage());
            }
        }
        log.debug("Worker {}: candidates={} handled={}", label, candidates.size(), ok);
    }
}
