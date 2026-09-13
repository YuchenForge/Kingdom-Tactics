package com.kingdom.api.service;

import com.kingdom.api.dto.ShopSlotDto;
import com.kingdom.api.entity.Round;
import com.kingdom.api.entity.ShopOffer;
import com.kingdom.api.repository.ShopOfferRepository;
import com.kingdom.engine.domain.UnitTypeResolver;
import com.kingdom.engine.planning.PlanningShop;
import com.kingdom.engine.planning.ShopGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Shop generation and persistence.
 * RNG is pure ShopGenerator; this layer owns JPA rows.
 */
@Service
public class ShopService {

    private final ShopOfferRepository shopOfferRepository;

    public ShopService(ShopOfferRepository shopOfferRepository) {
        this.shopOfferRepository = shopOfferRepository;
    }

    /**
     * Deterministic offer types for a player/round.
     *
     * refreshIndex 0 on round start, 1 on first refresh, and so on.
     */
    public List<String> generateOfferTypes(
            UUID gameId, int roundNumber, UUID playerId, int refreshIndex) {
        return ShopGenerator.generateOfferTypes(gameId, roundNumber, playerId, refreshIndex);
    }

    /** Opening shop for one player: refreshIndex=0, insert slots 0–2. */
    @Transactional
    public void createShopForPlayer(
            UUID roundId, UUID gameId, int roundNumber, UUID playerId) {
        List<String> types = generateOfferTypes(gameId, roundNumber, playerId, 0);
        List<ShopOffer> rows = new ArrayList<>(PlanningShop.SLOT_COUNT);
        for (int slot = 0; slot < PlanningShop.SLOT_COUNT; slot++) {
            rows.add(new ShopOffer(roundId, playerId, slot, types.get(slot)));
        }
        shopOfferRepository.saveAll(rows);
    }

    /** Opening shops for both seats of a round. */
    @Transactional
    public void createShopsForRound(
            Round round, UUID gameId, UUID player1Id, UUID player2Id) {
        createShopForPlayer(round.getId(), gameId, round.getRoundNumber(), player1Id);
        createShopForPlayer(round.getId(), gameId, round.getRoundNumber(), player2Id);
    }

    /** Mark a bought slot as sold (unit_type = null). */
    @Transactional
    public void consumeOffer(UUID roundId, UUID playerId, int slot) {
        ShopOffer offer = shopOfferRepository
                .findByRoundIdAndPlayerIdAndSlot(roundId, playerId, slot)
                .orElseThrow(() -> new IllegalArgumentException(
                        "shop offer not found: round=" + roundId
                                + " player=" + playerId
                                + " slot=" + slot));
        offer.setUnitType(null);
        shopOfferRepository.save(offer);
    }

    /**
     * Write already-rolled offer types to DB (update in place; create missing slots).
     * Used after a successful Refresh apply so DB matches the applier result.
     */
    @Transactional
    public void persistOffers(UUID roundId, UUID playerId, List<String> types) {
        if (types == null || types.size() != PlanningShop.SLOT_COUNT) {
            throw new IllegalArgumentException(
                    "types must have exactly " + PlanningShop.SLOT_COUNT + " entries");
        }
        for (int slot = 0; slot < PlanningShop.SLOT_COUNT; slot++) {
            final int slotIndex = slot;
            ShopOffer offer = shopOfferRepository
                    .findByRoundIdAndPlayerIdAndSlot(roundId, playerId, slot)
                    .orElseGet(() -> new ShopOffer(roundId, playerId, slotIndex, null));
            offer.setUnitType(types.get(slot));
            shopOfferRepository.save(offer);
        }
    }

    /**
     * Refresh helper: roll + persist in one call.
     * CommandService should prefer generateOfferTypes → apply → persistOffers
     * so the applier sees the same list that gets written.
     */
    @Transactional
    public void replaceOffers(
            UUID roundId, UUID gameId, int roundNumber, UUID playerId, int refreshIndex) {
        persistOffers(
                roundId,
                playerId,
                generateOfferTypes(gameId, roundNumber, playerId, refreshIndex));
    }

    /** Ordered rows → PlanningShop; null type = sold. */
    @Transactional(readOnly = true)
    public PlanningShop loadShop(UUID roundId, UUID playerId) {
        List<ShopOffer> rows =
                shopOfferRepository.findByRoundIdAndPlayerIdOrderBySlotAsc(roundId, playerId);
        String[] slots = new String[PlanningShop.SLOT_COUNT];
        for (ShopOffer row : rows) {
            int slot = row.getSlot();
            if (slot >= 0 && slot < PlanningShop.SLOT_COUNT) {
                slots[slot] = row.getUnitType();
            }
        }
        return new PlanningShop(Arrays.asList(slots));
    }

    /**
     * Map persisted offers → API DTOs for GET /state.
     * Always returns 3 slots; sold slots have unitType=null and cost=0.
     */
    public List<ShopSlotDto> toDtos(List<ShopOffer> offers) {
        String[] types = new String[PlanningShop.SLOT_COUNT];
        if (offers != null) {
            for (ShopOffer offer : offers) {
                int slot = offer.getSlot();
                if (slot >= 0 && slot < PlanningShop.SLOT_COUNT) {
                    types[slot] = offer.getUnitType();
                }
            }
        }
        List<ShopSlotDto> dtos = new ArrayList<>(PlanningShop.SLOT_COUNT);
        for (int slot = 0; slot < PlanningShop.SLOT_COUNT; slot++) {
            dtos.add(toDto(slot, types[slot]));
        }
        return dtos;
    }

    private static ShopSlotDto toDto(int slot, String unitType) {
        if (unitType == null) {
            return new ShopSlotDto(slot, null, 0);
        }
        int cost = UnitTypeResolver.resolve(unitType).getCost();
        return new ShopSlotDto(slot, unitType, cost);
    }
}
