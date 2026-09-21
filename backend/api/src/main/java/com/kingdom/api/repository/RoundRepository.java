package com.kingdom.api.repository;

import com.kingdom.api.entity.Round;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RoundRepository extends JpaRepository<Round, UUID> {

    Optional<Round> findByGameIdAndRoundNumber(UUID gameId, int roundNumber);

    List<Round> findByStateAndPlanningDeadlineLessThanEqual(String state, Instant deadline);

    /**
     * Candidate IDs for path 1 (LOCKED) and path 2 (RESOLVING).
     * Cap with PageRequest.of(0, n) so one scanner tick cannot grab unbounded backlog.
     */
    @Query("SELECT r.id FROM Round r WHERE r.state = :state ORDER BY r.startedAt")
    List<UUID> findIdsByState(@Param("state") String state, Pageable pageable);

    /**
     * Candidate IDs for path 3 (ROUND_RESULT with advanced_at IS NULL).
     * Uses idx_rounds_unadvanced (state, advanced_at).
     */
    @Query("""
            SELECT r.id FROM Round r
            WHERE r.state = :state AND r.advancedAt IS NULL
            ORDER BY r.finishedAt
            """)
    List<UUID> findIdsNeedingAdvance(@Param("state") String state, Pageable pageable);

    /**
     * TX1 claim: lock this round only if still LOCKED; skip if another worker holds it.
     */
    @Query(value = """
            SELECT * FROM rounds
            WHERE id = :id AND state = 'LOCKED'
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<Round> lockLockedRoundForClaim(@Param("id") UUID id);

    /**
     * TX2 commit: lock this round if still RESOLVING.
     * Plain FOR UPDATE (not SKIP LOCKED) — wait if another worker holds the row;
     * after they commit, re-check fails RESOLVING → no-op.
     */
    @Query(value = """
            SELECT * FROM rounds
            WHERE id = :id AND state = 'RESOLVING'
            FOR UPDATE
            """, nativeQuery = true)
    Optional<Round> lockResolvingRoundForCommit(@Param("id") UUID id);
}
