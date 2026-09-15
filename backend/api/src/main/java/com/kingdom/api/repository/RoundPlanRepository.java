package com.kingdom.api.repository;

import com.kingdom.api.entity.RoundPlan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RoundPlanRepository extends JpaRepository<RoundPlan, UUID> {

    Optional<RoundPlan> findByRoundIdAndPlayerId(UUID roundId, UUID playerId);

    List<RoundPlan> findByRoundId(UUID roundId);
}
