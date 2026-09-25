package com.kingdom.api.dto;

import java.util.Map;

public record RoundResultResponse(
        int roundNumber,
        String outcome,
        Map<String, Integer> keepDamage,
        Map<String, Integer> keepHpAfter,
        Map<String, EndSnapshotDto> endSnapshots
) {
}
