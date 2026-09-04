package com.kingdom.api.dto;

import java.time.Instant;
import java.util.UUID;

public record AuthResponse(
        UUID userId,
        String username,
        String email,
        String token,
        Instant createdAt,
        Long expiresIn
) {
}
