package com.kingdom.engine.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Represents a player's 4×4 placement board during the planning phase.
 * Units use local coordinates (x, y ∈ 0..3) and may occupy any cell on the board.
 * At combat start, two placement boards merge into a 4×8 {@link CombatBoard};
 * after combat, surviving units split back to their owner's placement board.
 */
public class Board {
    public static final int WIDTH = 4;
    public static final int HEIGHT = 4;

    private final Map<String, UnitInstance> unitMap;  // unitId -> UnitInstance
    private final int playerId;                         // 0 or 1 (for reference)

    public Board(int playerId) {
        this.unitMap = new HashMap<>();
        this.playerId = playerId;
    }

    /**
     * Copy constructor for snapshots.
     */
    public Board(Board other) {
        this.playerId = other.playerId;
        this.unitMap = new HashMap<>(other.unitMap);
    }

    /**
     * Add a unit to the board at given position.
     */
    public void addUnit(UnitInstance unit) {
        if (!isValidPosition(unit.getX(), unit.getY())) {
            throw new IllegalArgumentException("Invalid position: (" + unit.getX() + ", " + unit.getY() + ")");
        }
        if (unitMap.containsKey(unit.getId())) {
            throw new IllegalArgumentException("Unit already on board: " + unit.getId());
        }
        unitMap.put(unit.getId(), unit);
    }

    /**
     * Remove a unit from the board.
     */
    public void removeUnit(String unitId) {
        unitMap.remove(unitId);
    }

    /**
     * Get all units on the board.
     */
    public List<UnitInstance> getAllUnits() {
        return new ArrayList<>(unitMap.values());
    }

    /**
     * Get alive units sorted by ID (deterministic order).
     */
    public List<UnitInstance> getAliveUnits() {
        return getAllUnits().stream()
            .filter(UnitInstance::isAlive)
            .sorted(Comparator.comparing(UnitInstance::getId))
            .collect(Collectors.toList());
    }

    /**
     * Get dead units.
     */
    public List<UnitInstance> getDeadUnits() {
        return getAllUnits().stream()
            .filter(u -> !u.isAlive())
            .collect(Collectors.toList());
    }

    /**
     * Get a unit by ID.
     */
    public UnitInstance getUnit(String unitId) {
        return unitMap.get(unitId);
    }

    /**
     * Check if position is occupied.
     */
    public boolean isOccupied(int x, int y) {
        return unitMap.values().stream()
            .anyMatch(u -> u.getX() == x && u.getY() == y && u.isAlive());
    }

    /**
     * Get unit at position (if any).
     */
    public UnitInstance getUnitAt(int x, int y) {
        return unitMap.values().stream()
            .filter(u -> u.getX() == x && u.getY() == y && u.isAlive())
            .findFirst()
            .orElse(null);
    }

    /**
     * Get enemies of a unit (all alive units not in unitMap).
     * This assumes we have access to both boards during combat (passed as parameter).
     */
    public List<UnitInstance> getEnemiesInRange(UnitInstance unit, Board enemyBoard) {
        return enemyBoard.getAliveUnits().stream()
            .filter(enemy -> unit.chebyshevDistance(enemy) <= unit.getRange())
            .collect(Collectors.toList());
    }

    /**
     * Remove all dead units.
     */
    public void removeDead() {
        unitMap.values().removeIf(u -> !u.isAlive());
    }

    /**
     * Check if board is empty (no alive units).
     */
    public boolean isEmpty() {
        return getAliveUnits().isEmpty();
    }

    /**
     * Get total HP of alive units.
     */
    public int getTotalHp() {
        return getAliveUnits().stream()
            .mapToInt(UnitInstance::getCurrentHp)
            .sum();
    }

    /**
     * Validate board position.
     */
    public static boolean isValidPosition(int x, int y) {
        return x >= 0 && x < WIDTH && y >= 0 && y < HEIGHT;
    }

    /**
     * Get unit count.
     */
    public int getUnitCount() {
        return getAliveUnits().size();
    }

    public int getPlayerId() {
        return playerId;
    }

    @Override
    public String toString() {
        return String.format("Board(player:%d, units:%d)", playerId, getUnitCount());
    }
}
