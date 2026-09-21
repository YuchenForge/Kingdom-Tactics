package com.kingdom.api.repository;

import com.kingdom.api.entity.GameEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface GameEventRepository extends JpaRepository<GameEvent, UUID> {

    /** TX2 idempotency: events already committed for this round → no-op. */
    boolean existsByGameIdAndRoundNumber(UUID gameId, int roundNumber);
}
