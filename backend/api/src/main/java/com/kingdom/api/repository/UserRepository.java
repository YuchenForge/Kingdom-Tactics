package com.kingdom.api.repository;

import com.kingdom.api.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    boolean existsByUsername(String username);

    /**
     * TX3 ratings: lock both users in stable id order so ±25 is not lost under concurrency.
     */
    @Query(value = """
            SELECT * FROM users
            WHERE id IN (:idA, :idB)
            ORDER BY id ASC
            FOR UPDATE
            """, nativeQuery = true)
    List<User> lockTwoByIdsOrderByIdAsc(@Param("idA") UUID idA, @Param("idB") UUID idB);
}
