package com.kingdom.engine.planning;

import java.util.Objects;

import com.kingdom.engine.domain.UnitInstance;

/**
 * Immutable unit copy used during planning (shop / lane / board).
 * Shop buys always create level 1.
 */
public final class PlanningUnit {
    private final String id;
    private final String type;
    private final int level;

    public PlanningUnit(String id, String type, int level) {
        this.id = Objects.requireNonNull(id, "id");
        this.type = Objects.requireNonNull(type, "type");
        if (level < UnitInstance.MIN_LEVEL || level > UnitInstance.MAX_LEVEL) {
            throw new IllegalArgumentException(
                "level must be between " + UnitInstance.MIN_LEVEL
                    + " and " + UnitInstance.MAX_LEVEL + ": " + level);
        }
        this.level = level;
    }

    public static PlanningUnit fromShop(String id, String type) {
        return new PlanningUnit(id, type, UnitInstance.MIN_LEVEL);
    }

    public String getId() {
        return id;
    }

    public String getType() {
        return type;
    }

    public int getLevel() {
        return level;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PlanningUnit other)) {
            return false;
        }
        return level == other.level
            && id.equals(other.id)
            && type.equals(other.type);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, type, level);
    }

    @Override
    public String toString() {
        return "PlanningUnit{id='%s', type='%s', level=%d}".formatted(id, type, level);
    }
}
