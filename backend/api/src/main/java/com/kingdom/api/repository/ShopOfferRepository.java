package com.kingdom.api.repository;

import com.kingdom.api.entity.ShopOffer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ShopOfferRepository extends JpaRepository<ShopOffer, UUID> {

    List<ShopOffer> findByRoundIdAndPlayerIdOrderBySlotAsc(UUID roundId, UUID playerId);

    Optional<ShopOffer> findByRoundIdAndPlayerIdAndSlot(UUID roundId, UUID playerId, int slot);

    void deleteByRoundIdAndPlayerId(UUID roundId, UUID playerId);
}
