package com.kingdom.api.dto;

import java.time.Instant;

public record ApiError(
        String error,
        String message,
        int status,
        Instant timestamp,
        String requestId
) {
}
