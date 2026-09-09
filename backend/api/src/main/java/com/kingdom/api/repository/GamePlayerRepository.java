package com.kingdom.api.repository;

import com.kingdom.api.entity.GamePlayer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GamePlayerRepository extends JpaRepository<GamePlayer, UUID> {

    boolean existsByGameIdAndPlayerId(UUID gameId, UUID playerId);

    Optional<GamePlayer> findByGameIdAndPlayerId(UUID gameId, UUID playerId);

    List<GamePlayer> findByGameIdOrderBySeatAsc(UUID gameId);

    long countByGameId(UUID gameId);
}
