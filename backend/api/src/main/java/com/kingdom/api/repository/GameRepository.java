package com.kingdom.api.repository;

import com.kingdom.api.entity.Game;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface GameRepository extends JpaRepository<Game, UUID> {

    /**
     * Pessimistic lock on this game row.
     * JPQL + {@link LockModeType#PESSIMISTIC_WRITE} refreshes an already-managed entity
     * from the locked row (native {@code FOR UPDATE} alone does not).
     * Always acquire after the round lock (claim / resolve / advance / planning transition).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT g FROM Game g WHERE g.id = :id")
    Optional<Game> lockGameForUpdate(@Param("id") UUID id);
}
