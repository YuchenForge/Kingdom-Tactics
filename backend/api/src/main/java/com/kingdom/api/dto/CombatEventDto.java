package com.kingdom.api.dto;

import java.util.Map;

public record CombatEventDto(
        int sequenceNumber,
        String type,
        int tick,
        Map<String, Object> data
) {
}
