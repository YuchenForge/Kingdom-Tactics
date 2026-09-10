package com.kingdom.api.repository;

import com.kingdom.api.entity.Command;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CommandRepository extends JpaRepository<Command, UUID> {
}
