package com.kingdom.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record MatchResultResponse(
        UUID gameId,
        String state,
        UUID winnerId,
        String winnerUsername,
        String loserUsername,
        List<Integer> finalKeepHp,
        int finalRound,
        long durationSeconds,
        Instant finishedAt
) {
}
