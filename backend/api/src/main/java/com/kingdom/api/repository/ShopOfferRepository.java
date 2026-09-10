package com.kingdom.api.repository;

import com.kingdom.api.entity.ShopOffer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ShopOfferRepository extends JpaRepository<ShopOffer, UUID> {
}
