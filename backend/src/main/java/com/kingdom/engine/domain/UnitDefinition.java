package com.kingdom.engine.domain;

/**
 * Immutable definition of a unit type.
 * Stores Level-1 identity plus per-level HP/ATK tables (rules.md §5).
 */
public class UnitDefinition {
    private final String type;           // "Squire", "Knight", "Ranger", etc.
    private final int cost;              // Gold cost to recruit
    private final int range;             // Attack range (Chebyshev distance) — does not scale
    private final String specialAbility; // Unit-specific behavior
    /** Max HP at levels 1–3 (index 0 = Level 1). */
    private final int[] maxHpByLevel;
    /** Attack at levels 1–3 (index 0 = Level 1). */
    private final int[] attackByLevel;

    /** Healer heal amounts at levels 1–3. */
    public static final int[] HEAL_BY_LEVEL = {5, 7, 10};

    public UnitDefinition(
            String type,
            int cost,
            int[] maxHpByLevel,
            int[] attackByLevel,
            int range,
            String specialAbility) {
        if (maxHpByLevel == null || maxHpByLevel.length != 3) {
            throw new IllegalArgumentException("maxHpByLevel must have length 3");
        }
        if (attackByLevel == null || attackByLevel.length != 3) {
            throw new IllegalArgumentException("attackByLevel must have length 3");
        }
        this.type = type;
        this.cost = cost;
        this.maxHpByLevel = maxHpByLevel.clone();
        this.attackByLevel = attackByLevel.clone();
        this.range = range;
        this.specialAbility = specialAbility;
    }

    public String getType() { return type; }
    public int getCost() { return cost; }
    public int getRange() { return range; }
    public String getSpecialAbility() { return specialAbility; }

    /** Level-1 max HP (base table). */
    public int getMaxHp() { return maxHpByLevel[0]; }

    /** Level-1 attack (base table). */
    public int getAttack() { return attackByLevel[0]; }

    public int getMaxHp(int level) {
        return maxHpByLevel[levelIndex(level)];
    }

    public int getAttack(int level) {
        return attackByLevel[levelIndex(level)];
    }

    /**
     * Healer heal amount for {@code level}. Non-healers return 0.
     */
    public int getHealAmount(int level) {
        if (!"Healer".equals(type)) {
            return 0;
        }
        return HEAL_BY_LEVEL[levelIndex(level)];
    }

    private static int levelIndex(int level) {
        if (level < UnitInstance.MIN_LEVEL || level > UnitInstance.MAX_LEVEL) {
            throw new IllegalArgumentException(
                "level must be between " + UnitInstance.MIN_LEVEL
                    + " and " + UnitInstance.MAX_LEVEL + ": " + level);
        }
        return level - 1;
    }

    /**
     * Factory methods for all 6 unit types.
     * HP/ATK tables favor HP growth over ATK (~+35% L2 / ~+85% L3 vs L1
     * on strength ≈ HP + 4×ATK). See docs/rules.md §5.
     */
    public static UnitDefinition squire() {
        return new UnitDefinition(
            "Squire", 1,
            new int[] {8, 12, 17},
            new int[] {2, 3, 3},
            1, "None");
    }

    public static UnitDefinition shieldbearer() {
        return new UnitDefinition(
            "Shieldbearer", 2,
            new int[] {16, 24, 34},
            new int[] {1, 1, 1},
            1, "Armor");
    }

    public static UnitDefinition ranger() {
        return new UnitDefinition(
            "Ranger", 2,
            new int[] {7, 11, 18},
            new int[] {4, 5, 6},
            3, "LowestHpTarget");
    }

    public static UnitDefinition knight() {
        return new UnitDefinition(
            "Knight", 3,
            new int[] {18, 27, 38},
            new int[] {5, 6, 8},
            1, "NearestTarget");
    }

    public static UnitDefinition mage() {
        return new UnitDefinition(
            "Mage", 3,
            new int[] {9, 16, 25},
            new int[] {6, 7, 9},
            3, "SplashEvery3rdAttack");
    }

    public static UnitDefinition healer() {
        return new UnitDefinition(
            "Healer", 3,
            new int[] {10, 15, 22},
            new int[] {1, 1, 1},
            2, "HealEvery3rdAttack");
    }

    @Override
    public String toString() {
        return String.format(
            "%s(HP:%d, ATK:%d, RNG:%d, Cost:%d)",
            type, getMaxHp(), getAttack(), range, cost);
    }
}
