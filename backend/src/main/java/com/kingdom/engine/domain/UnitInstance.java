package com.kingdom.engine.domain;

import java.util.Objects;

/**
 * A unit instance in combat.
 * Mutable during combat (position, HP, cooldown change).
 */
public class UnitInstance {
    private final String id;              // Unique unit ID (e.g., "unit_001")
    private final UnitDefinition definition;
    private int currentHp;
    private int x, y;                     // Local placement or global combat position
    private Integer playerId;             // Set on merged combat board (0 or 1)
    private int cooldownTicks;            // Ticks until next action
    private int actionCounter;            // For Mage (splash) and Healer (heal) every 3rd attack
    private boolean alive;

    // Standard attack cooldown: 4 ticks = 1 second at 250ms per tick
    public static final int INITIAL_COOLDOWN = 4;

    public UnitInstance(String id, UnitDefinition definition, int x, int y) {
        this.id = Objects.requireNonNull(id);
        this.definition = Objects.requireNonNull(definition);
        this.currentHp = definition.getMaxHp();
        this.x = x;
        this.y = y;
        this.cooldownTicks = INITIAL_COOLDOWN;
        this.actionCounter = 0;
        this.alive = true;
    }

    // Getters
    public String getId() { return id; }
    public UnitDefinition getDefinition() { return definition; }
    public int getCurrentHp() { return currentHp; }
    public int getX() { return x; }
    public int getY() { return y; }
    public int getCooldownTicks() { return cooldownTicks; }
    public int getActionCounter() { return actionCounter; }
    public boolean isAlive() { return alive; }
    public String getType() { return definition.getType(); }
    public int getMaxHp() { return definition.getMaxHp(); }
    public int getAttack() { return definition.getAttack(); }
    public int getRange() { return definition.getRange(); }
    public Integer getPlayerId() { return playerId; }

    public void setPlayerId(int playerId) {
        if (playerId != 0 && playerId != 1) {
            throw new IllegalArgumentException("playerId must be 0 or 1: " + playerId);
        }
        this.playerId = playerId;
    }

    void setCurrentHp(int currentHp) {
        this.currentHp = currentHp;
    }

    void setCooldownTicks(int cooldownTicks) {
        this.cooldownTicks = cooldownTicks;
    }

    void setActionCounter(int actionCounter) {
        this.actionCounter = actionCounter;
    }

    void setAlive(boolean alive) {
        this.alive = alive;
    }

    /**
     * Decrement cooldown by 1 tick.
     */
    public void decrementCooldown() {
        if (cooldownTicks > 0) {
            cooldownTicks--;
        }
    }

    /**
     * Check if unit can act (cooldown is 0).
     */
    public boolean canAct() {
        return alive && cooldownTicks == 0;
    }

    /**
     * Reset cooldown to initial value after acting.
     */
    public void resetCooldown() {
        cooldownTicks = INITIAL_COOLDOWN;
    }

    /**
     * Increment attack counter. Used for Mage (splash) and Healer (heal) every 3rd attack.
     */
    public void incrementActionCounter() {
        actionCounter++;
    }

    /**
     * Check if attack counter is at a multiple of 3 (triggers special ability).
     */
    public boolean isTriggerSpecialAction() {
        return actionCounter % 3 == 0;
    }

    /**
     * True if the next attack action will be the 3rd, 6th, 9th, … (heal or splash turn).
     */
    public boolean willTriggerSpecialOnNextAttack() {
        return (actionCounter + 1) % 3 == 0;
    }

    /**
     * Set unit position to (x, y)
     */
    public void setPosition(int x, int y) {
        this.x = x;
        this.y = y;
    }

    /**
     * Take damage. Minimum 1 damage always.
     */
    public void takeDamage(int damage) {
        int actualDamage = Math.max(1, damage);
        currentHp -= actualDamage;
        if (currentHp <= 0) {
            currentHp = 0;
            alive = false;
        }
    }

    /**
     * Heal unit. Capped at max HP.
     */
    public void heal(int amount) {
        currentHp = Math.min(currentHp + amount, definition.getMaxHp());
    }

    /**
     * Chebyshev distance (max of |Δx| and |Δy|).
     */
    public int chebyshevDistance(UnitInstance other) {
        return Math.max(Math.abs(this.x - other.x), Math.abs(this.y - other.y));
    }

    /**
     * Manhattan distance (|Δx| + |Δy|).
     */
    public int manhattanDistance(UnitInstance other) {
        return Math.abs(this.x - other.x) + Math.abs(this.y - other.y);
    }

    /**
     * Get armor reduction (1 for Shieldbearer, 0 for others).
     */
    public int getArmor() {
        return "Shieldbearer".equals(definition.getType()) ? 1 : 0;
    }

    @Override
    public String toString() {
        return String.format("%s(%s, HP:%d/%d, pos:(%d,%d), cd:%d)", 
            id, definition.getType(), currentHp, definition.getMaxHp(), x, y, cooldownTicks);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        UnitInstance that = (UnitInstance) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
