package com.kingdom.api.dto;

/**
 * Server-authored unit view for planning UI: identity plus effective display stats
 * at the unit's level (from {@code UnitDefinition}). Clients must not recompute
 * balance tables.
 */
public record UnitViewDto(
        String id,
        String unitType,
        int level,
        int maxHp,
        int attack,
        int range,
        String specialAbility,
        int healAmount,
        int sellRefund
) {
}
