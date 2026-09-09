package com.kingdom.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record GameResponse(
        UUID gameId,
        String state,
        int currentRound,
        List<PlayerSummary> players,
        Instant planningDeadline, // null while WAITING
        Instant createdAt,
        Instant startedAt
) {
}
