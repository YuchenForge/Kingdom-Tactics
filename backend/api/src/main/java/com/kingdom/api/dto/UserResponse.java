package com.kingdom.api.dto;

import java.time.Instant;
import java.util.UUID;

public record UserResponse(
        UUID userId,
        String username,
        String email,
        int rating,
        Instant createdAt
) {
}
