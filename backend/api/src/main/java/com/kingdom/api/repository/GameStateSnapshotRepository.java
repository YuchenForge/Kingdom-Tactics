package com.kingdom.api.repository;

import com.kingdom.api.entity.GameStateSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface GameStateSnapshotRepository extends JpaRepository<GameStateSnapshot, UUID> {

    /**
     * Snapshots for a round. Pass roundStart=false for TX2 end snaps
     * (survivors + Keep HP) used by round/match result reads.
     */
    List<GameStateSnapshot> findByGameIdAndRoundNumberAndRoundStart(
            UUID gameId,
            int roundNumber,
            boolean roundStart
    );
}
