package com.kingdom.engine.planning;

/**
 * Why a planning command failed.
 */
public enum PlanningError {
    /** Round/plan is locked; no further edits (→ 423). */
    LOCKED(423),

    /** Lock command when already locked (→ 423). */
    ALREADY_LOCKED(423),

    /** Planning deadline expired; mutation not applied (→ 423). */
    DEADLINE_PASSED(423),

    /** Not enough gold for buy or refresh (→ 400). */
    INSUFFICIENT_GOLD(400),

    /** Holding lane has no empty slot for a buy (→ 400). */
    LANE_FULL(400),

    /** Shop slot is null / already sold (→ 409). */
    EMPTY_SHOP_SLOT(409),

    /** Shop offer (or similar) names an unknown unit type (→ 400). */
    INVALID_UNIT_TYPE(400),

    /** Board coordinates outside 0..3 (→ 400). */
    OUT_OF_BOUNDS(400),

    /** Target board cell already occupied (→ 409). */
    CELL_OCCUPIED(409),

    /** Target holding-lane slot already occupied (→ 409). */
    SLOT_OCCUPIED(409),

    /** Unit id not on lane or board (→ 404). */
    UNIT_NOT_FOUND(404),

    /** Entering the board would exceed the round's unit cap (→ 400). */
    BOARD_CAP_EXCEEDED(400),

    /** Refresh offers wrong size or unknown unit type (→ 400). */
    INVALID_REFRESH_OFFERS(400);

    private final int httpStatus;

    PlanningError(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    /** Suggested HTTP status when this error is mapped at the API boundary. */
    public int httpStatus() {
        return httpStatus;
    }
}
