package com.kingdom.worker.service;

import java.util.UUID;

/** Snapshot returned after TX1 commits — safe to use outside the claim transaction. */
public record ClaimedRound(
        UUID roundId,
        UUID gameId,
        int roundNumber,
        long combatSeed) {
}
