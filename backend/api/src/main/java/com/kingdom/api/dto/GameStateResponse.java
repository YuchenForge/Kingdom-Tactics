package com.kingdom.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record GameStateResponse(
        UUID gameId,
        String state,
        int currentRound,
        int yourSeat,
        int yourKeepHp,
        int yourGold,
        int opponentKeepHp,
        int opponentUnitCount,
        List<List<String>> yourBoard, // 4x4; all null in Phase 2
        List<LaneSlotDto> yourLane,   // empty unit stubs in Phase 2
        List<ShopSlotDto> shop,       // empty list in Phase 2; richer slots in Phase 3
        Instant planningDeadline,
        boolean isLocked,
        boolean opponentIsLocked
) {
}
