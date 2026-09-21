package com.kingdom.engine.domain;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable record of a combat event.
 * Used for logging and replay.
 */
public class CombatEvent {
    public enum EventType {
        UNIT_PLACED,      // Unit placed at start of combat
        UNIT_MOVED,       // Unit moved one tile
        ATTACK,           // Unit attacked (includes splash)
        HEALED,           // Unit was healed
        UNIT_DIED,        // Unit reduced to 0 HP
        COMBAT_ENDED      // Combat finished (time limit or no survivors)
    }

    private final EventType type;
    private final int tick;
    private final Map<String, Object> data;

    // Immutability
    public CombatEvent(EventType type, int tick, Map<String, Object> data) {
        this.type = Objects.requireNonNull(type);
        this.tick = tick;
        this.data = new HashMap<>(data);  // Defensive copy
    }

    // Getters
    public EventType getType() { return type; }
    public int getTick() { return tick; }
    public Map<String, Object> getData() { return new HashMap<>(data); }

    /**
     * Factory method for UNIT_PLACED event (global combat coords; includes level/HP for playback).
     */
    public static CombatEvent unitPlaced(int tick, UnitInstance unit) {
        Map<String, Object> data = new HashMap<>();
        data.put("unitId", unit.getId());
        data.put("unitType", unit.getType());
        data.put("x", unit.getX());
        data.put("y", unit.getY());
        data.put("playerId", unit.getPlayerId());
        data.put("level", unit.getLevel());
        data.put("currentHp", unit.getCurrentHp());
        data.put("maxHp", unit.getMaxHp());
        return new CombatEvent(EventType.UNIT_PLACED, tick, data);
    }

    /**
     * Factory method for UNIT_MOVED event.
     */
    public static CombatEvent unitMoved(int tick, String unitId, int x, int y, int playerId) {
        Map<String, Object> data = new HashMap<>();
        data.put("unitId", unitId);
        data.put("x", x);
        data.put("y", y);
        data.put("playerId", playerId);
        return new CombatEvent(EventType.UNIT_MOVED, tick, data);
    }

    /**
     * Factory method for ATTACK event.
     */
    public static CombatEvent attack(int tick, String attackerId, String targetId, int damage) {
        Map<String, Object> data = new HashMap<>();
        data.put("attackerId", attackerId);
        data.put("targetId", targetId);
        data.put("damage", damage);
        return new CombatEvent(EventType.ATTACK, tick, data);
    }

    /**
     * Factory method for HEALED event.
     */
    public static CombatEvent healed(int tick, String healerId, String targetId, int amount) {
        Map<String, Object> data = new HashMap<>();
        data.put("healerId", healerId);
        data.put("targetId", targetId);
        data.put("amount", amount);
        return new CombatEvent(EventType.HEALED, tick, data);
    }

    /**
     * Factory method for UNIT_DIED event.
     */
    public static CombatEvent unitDied(int tick, String unitId) {
        Map<String, Object> data = new HashMap<>();
        data.put("unitId", unitId);
        return new CombatEvent(EventType.UNIT_DIED, tick, data);
    }

    /**
     * Factory method for COMBAT_ENDED event.
     */
    public static CombatEvent combatEnded(int tick, String reason) {
        Map<String, Object> data = new HashMap<>();
        data.put("reason", reason);
        return new CombatEvent(EventType.COMBAT_ENDED, tick, data);
    }

    @Override
    public String toString() {
        return String.format("CombatEvent(tick:%d, type:%s, data:%s)", tick, type, data);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CombatEvent that = (CombatEvent) o;
        return tick == that.tick && 
               type == that.type && 
               Objects.equals(data, that.data);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, tick, data);
    }
}
