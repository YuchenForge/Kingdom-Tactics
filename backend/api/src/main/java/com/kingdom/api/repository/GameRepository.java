package com.kingdom.api.repository;

import com.kingdom.api.entity.Game;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface GameRepository extends JpaRepository<Game, UUID> {

    /** TX1: lock game row in the same claim transaction as the round. */
    @Query(value = """
            SELECT * FROM games WHERE id = :id FOR UPDATE
            """, nativeQuery = true)
    Optional<Game> lockGameForUpdate(@Param("id") UUID id);
}
