package com.kingdom.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * JSON body returned by every planning command (buy, sell, refresh, relocate, lock)
 * including when the same Idempotency-Key is retried.
 */

// drops null lock fields on buy, sell, refresh, relocate commands
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CommandResponse(
        boolean success,
        int gold,
        List<LaneSlotDto> lane,
        List<List<String>> board,
        Map<String, UnitViewDto> units,
        List<ShopSlotDto> shop,
        boolean isLocked,
        Boolean opponentIsLocked,
        String message,
        String nextState
) {

    // buy, sell, refresh, relocate, and idempotency retries
    public static CommandResponse snapshot(
            int gold,
            List<LaneSlotDto> lane,
            List<List<String>> board,
            Map<String, UnitViewDto> units,
            List<ShopSlotDto> shop,
            boolean isLocked) {
        return new CommandResponse(true, gold, lane, board, units, shop, isLocked, null, null, null);
    }

    // lock endpoint
    public static CommandResponse lock(
            int gold,
            List<LaneSlotDto> lane,
            List<List<String>> board,
            Map<String, UnitViewDto> units,
            List<ShopSlotDto> shop,
            boolean opponentIsLocked,
            String message,
            String nextState) {
        return new CommandResponse(
                true,
                gold,
                lane,
                board,
                units,
                shop,
                true,
                opponentIsLocked,
                message,
                nextState);
    }
}
