package com.kingdom.api.dto;

import java.util.List;

public record EventsResponse(
        int roundNumber,
        List<CombatEventDto> events,
        int nextAfterSequence,
        boolean hasMore,
        boolean complete
) {
}
