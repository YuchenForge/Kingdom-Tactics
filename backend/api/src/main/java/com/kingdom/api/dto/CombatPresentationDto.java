package com.kingdom.api.dto;

import java.time.Instant;

/** Shared persisted playback schedule; independent of the viewing player's seat. */
public record CombatPresentationDto(
        int roundNumber,
        Instant startsAt,
        Instant combatEndsAt,
        Instant endsAt,
        int tickDurationMs
) {
}
