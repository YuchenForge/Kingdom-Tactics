package com.kingdom.api.repository;

import com.kingdom.api.entity.GamePlayer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GamePlayerRepository extends JpaRepository<GamePlayer, UUID> {

    boolean existsByGameIdAndPlayerId(UUID gameId, UUID playerId);

    Optional<GamePlayer> findByGameIdAndPlayerId(UUID gameId, UUID playerId);

    List<GamePlayer> findByGameIdOrderBySeatAsc(UUID gameId);

    long countByGameId(UUID gameId);

    /**
     * TX2: lock both seats in seat order before applying Keep damage (avoid deadlocks).
     */
    @Query(value = """
            SELECT * FROM game_players
            WHERE game_id = :gameId
            ORDER BY seat ASC
            FOR UPDATE
            """, nativeQuery = true)
    List<GamePlayer> lockByGameIdOrderBySeatAsc(@Param("gameId") UUID gameId);
}
