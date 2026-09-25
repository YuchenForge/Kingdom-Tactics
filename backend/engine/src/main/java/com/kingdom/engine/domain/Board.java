package com.kingdom.engine.domain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A player's 4×4 placement board during planning.
 * Units use local coordinates (x, y ∈ 0..3).
 * At combat start, two placement boards merge into a 4×8 {@link CombatBoard}.
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

    /** Add a unit to the board at its current position. */
    public void addUnit(UnitInstance unit) {
        if (!isValidPosition(unit.getX(), unit.getY())) {
            throw new IllegalArgumentException(
                    "Invalid position: (" + unit.getX() + ", " + unit.getY() + ")");
        }
        if (unitMap.containsKey(unit.getId())) {
            throw new IllegalArgumentException("Unit already on board: " + unit.getId());
        }
        unitMap.put(unit.getId(), unit);
    }

    /** All placed units (placement boards do not track combat HP/death). */
    public List<UnitInstance> getAllUnits() {
        return new ArrayList<>(unitMap.values());
    }

    public UnitInstance getUnit(String unitId) {
        return unitMap.get(unitId);
    }

    public boolean isEmpty() {
        return unitMap.isEmpty();
    }

    public static boolean isValidPosition(int x, int y) {
        return x >= 0 && x < WIDTH && y >= 0 && y < HEIGHT;
    }

    public int getUnitCount() {
        return unitMap.size();
    }

    public int getPlayerId() {
        return playerId;
    }

    @Override
    public String toString() {
        return String.format("Board(player:%d, units:%d)", playerId, getUnitCount());
    }
}
