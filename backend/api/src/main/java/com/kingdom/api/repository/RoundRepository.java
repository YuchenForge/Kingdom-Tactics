package com.kingdom.api.repository;

import com.kingdom.api.entity.Round;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface RoundRepository extends JpaRepository<Round, UUID> {

    Optional<Round> findByGameIdAndRoundNumber(UUID gameId, int roundNumber);
}
