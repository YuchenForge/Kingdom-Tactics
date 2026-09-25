package com.kingdom.api.repository;

import com.kingdom.api.entity.GameEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface GameEventRepository extends JpaRepository<GameEvent, UUID> {

    /**
     * Page combat events after afterSequence (exclusive), ascending by sequence.
     * Pass PageRequest.of(0, limit) (or limit+1 for hasMore) from the API layer.
     */
    List<GameEvent> findByGameIdAndRoundNumberAndSequenceNumGreaterThanOrderBySequenceNumAsc(
            UUID gameId,
            int roundNumber,
            int afterSequence,
            Pageable pageable
    );
}
