package com.kingdom.api.repository;

import com.kingdom.api.entity.Command;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CommandRepository extends JpaRepository<Command, UUID> {

    Optional<Command> findByRoundPlanIdAndIdempotencyKey(UUID roundPlanId, UUID idempotencyKey);

    int countByRoundPlanId(UUID roundPlanId);

    long countByRoundPlanIdAndCommandType(UUID roundPlanId, String commandType);
}
