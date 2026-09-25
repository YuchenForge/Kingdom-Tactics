package com.kingdom.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record GameStateResponse(
        UUID gameId,
        String state,
        int currentRound,
        Integer latestResolvedRound, // null until first TX 2
        int yourSeat,
        int yourKeepHp,
        int yourGold,
        int opponentKeepHp,
        int opponentUnitCount,
        List<List<String>> yourBoard, // 4x4 unit ids (or null)
        Map<String, UnitViewDto> yourUnits, // id → type/level/display stats (board + lane)
        List<LaneSlotDto> yourLane,
        List<ShopSlotDto> shop,
        Instant planningDeadline,
        boolean isLocked,
        boolean opponentIsLocked
) {
}
