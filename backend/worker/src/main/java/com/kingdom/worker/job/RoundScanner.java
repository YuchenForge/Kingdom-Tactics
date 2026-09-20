package com.kingdom.worker.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls for rounds needing claim / resolve retry / advancement.
 */
@Component
@ConditionalOnProperty(prefix = "app.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RoundScanner {

    private static final Logger log = LoggerFactory.getLogger(RoundScanner.class);

    @Scheduled(fixedDelayString = "${app.worker.poll-ms:10000}")
    public void poll() {
        log.debug("RoundScanner tick (claim/resolve/advance not wired yet)");
    }
}
