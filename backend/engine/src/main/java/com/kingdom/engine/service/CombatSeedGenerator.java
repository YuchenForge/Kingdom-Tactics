package com.kingdom.engine.service;

import java.util.Objects;
import java.util.UUID;

/**
 * Deterministic combat seed from game + round.
 * Same inputs → same seed across JVMs; only used when round.combatSeed is null.
 */
public final class CombatSeedGenerator {

    private CombatSeedGenerator() {
        // static utility
    }

    /**
     * Mix UUID bits + round into a long seed (same fold style as ShopGenerator.seed).
     */
    public static long seed(UUID gameId, int roundNumber) {
        Objects.requireNonNull(gameId, "gameId");
        long h = 1L;
        h = 31 * h + gameId.getMostSignificantBits();
        h = 31 * h + gameId.getLeastSignificantBits();
        h = 31 * h + roundNumber;
        return h;
    }
}
