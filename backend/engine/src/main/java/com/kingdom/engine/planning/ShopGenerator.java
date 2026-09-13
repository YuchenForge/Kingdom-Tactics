package com.kingdom.engine.planning;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;

import com.kingdom.engine.domain.UnitTypeResolver;

/**
 * Side-effect-free shop offer generation.
 * Same (gameId, roundNumber, playerId, refreshIndex) → same three types.
 */
public final class ShopGenerator {

    private static final String[] POOL = UnitTypeResolver.ORDERED_TYPES.toArray(String[]::new);

    private ShopGenerator() {
        // static utility
    }

    /**
     * @param refreshIndex 0 = opening shop for the round; 1 = first refresh; …
     */
    public static List<String> generateOfferTypes(
            UUID gameId, int roundNumber, UUID playerId, int refreshIndex) {
        Objects.requireNonNull(gameId, "gameId");
        Objects.requireNonNull(playerId, "playerId");
        if (refreshIndex < 0) {
            throw new IllegalArgumentException("refreshIndex must be >= 0: " + refreshIndex);
        }

        Random rng = new Random(seed(gameId, roundNumber, playerId, refreshIndex));
        List<String> offers = new ArrayList<>(PlanningShop.SLOT_COUNT);
        for (int i = 0; i < PlanningShop.SLOT_COUNT; i++) {
            offers.add(POOL[rng.nextInt(POOL.length)]);
        }
        return List.copyOf(offers);
    }

    /**
     * Mix UUID bits + round + refresh into a long seed.
     * Fold each half separately — XOR of identical halves would erase the player/game id.
     */
    public static long seed(UUID gameId, int roundNumber, UUID playerId, int refreshIndex) {
        long h = 1L;
        h = 31 * h + gameId.getMostSignificantBits();
        h = 31 * h + gameId.getLeastSignificantBits();
        h = 31 * h + roundNumber;
        h = 31 * h + playerId.getMostSignificantBits();
        h = 31 * h + playerId.getLeastSignificantBits();
        h = 31 * h + refreshIndex;
        return h;
    }
}
