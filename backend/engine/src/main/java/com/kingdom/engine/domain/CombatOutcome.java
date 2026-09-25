package com.kingdom.engine.domain;

/**
 * Canonical combat end reasons: {@code rounds.outcome} and {@code COMBAT_ENDED.data.reason}.
 *
 * PLAYER_VICTORY / ENEMY_VICTORY are seat-relative:
 * "player" means seat 0, "enemy" means seat 1. They are not relative to the HTTP
 * requesting user (Alice in seat 1 still sees {@code ENEMY_VICTORY} when she wins).
 */
public enum CombatOutcome {
    PLAYER_VICTORY,
    ENEMY_VICTORY,
    TIME_LIMIT,
    DRAW;

    /** Parse a persisted / event reason string; rejects null and unknown names. */
    public static CombatOutcome requireKnown(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Unknown combat outcome: null");
        }
        try {
            return valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "Unknown combat outcome: " + value, ex);
        }
    }
}
