package com.kingdom.api.dto;

import java.util.UUID;

public record PlayerSummary(
        UUID playerId,
        String username,
        int keepHp,
        int gold,
        int seat
) {
}
