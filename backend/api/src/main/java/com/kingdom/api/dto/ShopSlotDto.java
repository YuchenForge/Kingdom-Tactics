package com.kingdom.api.dto;

/**
 * Shop offer slot. When {@code unitType} is null the slot is sold/empty and
 * numeric display fields are 0 / {@code specialAbility} is null.
 * Recruit offers always use Level-1 stats from the server definition.
 */
public record ShopSlotDto(
        int slot,
        String unitType,
        int cost,
        int maxHp,
        int attack,
        int range,
        String specialAbility,
        int healAmount
) {
}
