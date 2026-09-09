package com.kingdom.api.dto;

public record LaneSlotDto(
        int slot,
        String unitId,
        String unitType,
        Integer level
) {
}
