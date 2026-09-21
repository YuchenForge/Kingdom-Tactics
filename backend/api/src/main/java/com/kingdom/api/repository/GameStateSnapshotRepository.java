package com.kingdom.api.repository;

import com.kingdom.api.entity.GameStateSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface GameStateSnapshotRepository extends JpaRepository<GameStateSnapshot, UUID> {
}
