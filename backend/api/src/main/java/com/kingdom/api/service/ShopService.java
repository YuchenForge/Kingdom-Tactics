package com.kingdom.api.service;

import com.kingdom.api.entity.Round;
import com.kingdom.api.entity.ShopOffer;
import com.kingdom.api.repository.ShopOfferRepository;
import com.kingdom.engine.planning.PlanningShop;
import com.kingdom.engine.planning.ShopGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Shop persistence. Offer RNG lives in {@link ShopGenerator}; this layer owns JPA rows.
 */
@Service
public class ShopService {

    private final ShopOfferRepository shopOfferRepository;

    public ShopService(ShopOfferRepository shopOfferRepository) {
        this.shopOfferRepository = shopOfferRepository;
    }

    /** Opening shops for both seats of a round (refreshIndex=0). */
    @Transactional
    public void createShopsForRound(
            Round round, UUID gameId, UUID player1Id, UUID player2Id) {
        createShopForPlayer(round.getId(), gameId, round.getRoundNumber(), player1Id);
        createShopForPlayer(round.getId(), gameId, round.getRoundNumber(), player2Id);
    }

    private void createShopForPlayer(
            UUID roundId, UUID gameId, int roundNumber, UUID playerId) {
        List<String> types = ShopGenerator.generateOfferTypes(gameId, roundNumber, playerId, 0);
        List<ShopOffer> rows = new ArrayList<>(PlanningShop.SLOT_COUNT);
        for (int slot = 0; slot < PlanningShop.SLOT_COUNT; slot++) {
            rows.add(new ShopOffer(roundId, playerId, slot, types.get(slot)));
        }
        shopOfferRepository.saveAll(rows);
    }

    /** Mark a bought slot as sold (unit_type = null). */
    @Transactional
    public void consumeOffer(UUID roundId, UUID playerId, int slot) {
        ShopOffer offer = requireOffer(roundId, playerId, slot);
        offer.setUnitType(null);
        shopOfferRepository.save(offer);
    }

    /**
     * Update existing offer rows after a successful Refresh apply.
     * Missing slots are corrupt state — not created here.
     */
    @Transactional
    public void persistOffers(UUID roundId, UUID playerId, List<String> types) {
        if (types == null || types.size() != PlanningShop.SLOT_COUNT) {
            throw new IllegalArgumentException(
                    "types must have exactly " + PlanningShop.SLOT_COUNT + " entries");
        }
        for (int slot = 0; slot < PlanningShop.SLOT_COUNT; slot++) {
            ShopOffer offer = requireOffer(roundId, playerId, slot);
            offer.setUnitType(types.get(slot));
            shopOfferRepository.save(offer);
        }
    }

    /** Ordered rows → PlanningShop; null type = sold. Requires slots 0..2 exactly once. */
    @Transactional(readOnly = true)
    public PlanningShop loadShop(UUID roundId, UUID playerId) {
        List<ShopOffer> rows =
                shopOfferRepository.findByRoundIdAndPlayerIdOrderBySlotAsc(roundId, playerId);
        if (rows.size() != PlanningShop.SLOT_COUNT) {
            throw new IllegalStateException(
                    "expected " + PlanningShop.SLOT_COUNT + " shop slots for round="
                            + roundId + " player=" + playerId + ", got " + rows.size());
        }
        String[] offers = new String[PlanningShop.SLOT_COUNT];
        for (int i = 0; i < PlanningShop.SLOT_COUNT; i++) {
            ShopOffer row = rows.get(i);
            if (row.getSlot() != i) {
                throw new IllegalStateException(
                        "invalid shop slot layout round=" + roundId + " player=" + playerId
                                + ": expected slot " + i + ", got " + row.getSlot());
            }
            offers[i] = row.getUnitType();
        }
        return new PlanningShop(Arrays.asList(offers));
    }

    private ShopOffer requireOffer(UUID roundId, UUID playerId, int slot) {
        return shopOfferRepository
                .findByRoundIdAndPlayerIdAndSlot(roundId, playerId, slot)
                .orElseThrow(() -> new IllegalArgumentException(
                        "shop offer not found: round=" + roundId
                                + " player=" + playerId
                                + " slot=" + slot));
    }
}
