package com.kingdom.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuthResponse(
        UUID userId,
        String username,
        String email,
        String token,
        Instant createdAt,
        Long expiresIn
) {
}
