package com.kingdom.engine.domain;

/**
 * Immutable definition of a unit type.
 * Defines the stats and special abilities for a unit class.
 */
public class UnitDefinition {
    private final String type;           // "Squire", "Knight", "Ranger", etc.
    private final int cost;              // Gold cost to recruit
    private final int maxHp;             // Maximum hit points
    private final int attack;            // Attack power
    private final int range;             // Attack range (Chebyshev distance)
    private final String specialAbility; // Unit-specific behavior

    public UnitDefinition(String type, int cost, int maxHp, int attack, int range, String specialAbility) {
        this.type = type;
        this.cost = cost;
        this.maxHp = maxHp;
        this.attack = attack;
        this.range = range;
        this.specialAbility = specialAbility;
    }

    // Getters
    public String getType() { return type; }
    public int getCost() { return cost; }
    public int getMaxHp() { return maxHp; }
    public int getAttack() { return attack; }
    public int getRange() { return range; }
    public String getSpecialAbility() { return specialAbility; }

    /**
     * Factory methods for all 6 unit types
     */
    public static UnitDefinition squire() {
        return new UnitDefinition("Squire", 1, 8, 2, 1, "None");
    }

    public static UnitDefinition shieldbearer() {
        return new UnitDefinition("Shieldbearer", 2, 16, 1, 1, "Armor");
    }

    public static UnitDefinition ranger() {
        return new UnitDefinition("Ranger", 2, 7, 4, 3, "LowestHpTarget");
    }

    public static UnitDefinition knight() {
        return new UnitDefinition("Knight", 3, 18, 5, 1, "NearestTarget");
    }

    public static UnitDefinition mage() {
        return new UnitDefinition("Mage", 3, 9, 6, 3, "SplashEvery3rdAction");
    }

    public static UnitDefinition healer() {
        return new UnitDefinition("Healer", 3, 10, 1, 2, "HealEvery3rdAction");
    }

    @Override
    public String toString() {
        return String.format("%s(HP:%d, ATK:%d, RNG:%d, Cost:%d)", type, maxHp, attack, range, cost);
    }
}
