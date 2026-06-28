package com.kingdom.engine.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Merged 4×8 combat board formed by stacking two placement boards vertically.
 * Exists only during combat resolution. Placement boards are restored separately
 * after the round ends.
 */
public class CombatBoard {
    public static final int WIDTH = Coordinates.COMBAT_WIDTH;
    public static final int HEIGHT = Coordinates.COMBAT_HEIGHT;

    private final Map<String, UnitInstance> unitMap;
    private final Map<String, Integer> unitPlayerIds;

    public CombatBoard() {
        this.unitMap = new HashMap<>();
        this.unitPlayerIds = new HashMap<>();
    }

    public CombatBoard(CombatBoard other) {
        this.unitMap = new HashMap<>();
        this.unitPlayerIds = new HashMap<>(other.unitPlayerIds);
        for (Map.Entry<String, UnitInstance> entry : other.unitMap.entrySet()) {
            UnitInstance copy = copyUnit(entry.getValue());
            this.unitMap.put(entry.getKey(), copy);
        }
    }

    /**
     * Merge two placement boards into a single combat board using global coordinates.
     * Every unit enters combat at full HP regardless of prior state.
     */
    public static CombatBoard merge(Board player0Board, Board player1Board) {
        CombatBoard combatBoard = new CombatBoard();
        combatBoard.addUnitsFromPlacementBoard(player0Board, 0);
        combatBoard.addUnitsFromPlacementBoard(player1Board, 1);
        return combatBoard;
    }

    private void addUnitsFromPlacementBoard(Board placementBoard, int playerId) {
        if (placementBoard.getPlayerId() != playerId) {
            throw new IllegalArgumentException(
                "Board playerId " + placementBoard.getPlayerId() + " does not match " + playerId);
        }
        for (UnitInstance unit : placementBoard.getAllUnits()) {
            int combatX = Coordinates.toCombatX(unit.getX(), playerId);
            int combatY = Coordinates.toCombatY(unit.getY(), playerId);
            UnitInstance combatUnit = copyForCombat(unit, combatX, combatY, playerId);
            addUnit(combatUnit);
        }
    }

    private static UnitInstance copyForCombat(UnitInstance unit, int combatX, int combatY, int playerId) {
        UnitInstance copy = new UnitInstance(unit.getId(), unit.getDefinition(), combatX, combatY);
        copy.setPlayerId(playerId);
        return copy;
    }

    private static UnitInstance copyUnit(UnitInstance unit) {
        UnitInstance copy = new UnitInstance(unit.getId(), unit.getDefinition(), unit.getX(), unit.getY());
        copy.setCurrentHp(unit.getCurrentHp());
        copy.setCooldownTicks(unit.getCooldownTicks());
        copy.setActionCounter(unit.getActionCounter());
        copy.setAlive(unit.isAlive());
        if (unit.getPlayerId() != null) {
            copy.setPlayerId(unit.getPlayerId());
        }
        return copy;
    }

    public void addUnit(UnitInstance unit) {
        if (!isValidPosition(unit.getX(), unit.getY())) {
            throw new IllegalArgumentException("Invalid position: (" + unit.getX() + ", " + unit.getY() + ")");
        }
        if (unit.getPlayerId() == null) {
            throw new IllegalArgumentException("Combat units must have a playerId");
        }
        if (unitMap.containsKey(unit.getId())) {
            throw new IllegalArgumentException("Unit already on board: " + unit.getId());
        }
        if (isOccupied(unit.getX(), unit.getY())) {
            throw new IllegalArgumentException("Position occupied: (" + unit.getX() + ", " + unit.getY() + ")");
        }
        unitMap.put(unit.getId(), unit);
        unitPlayerIds.put(unit.getId(), unit.getPlayerId());
    }

    public void removeUnit(String unitId) {
        unitMap.remove(unitId);
        unitPlayerIds.remove(unitId);
    }

    public List<UnitInstance> getAllUnits() {
        return new ArrayList<>(unitMap.values());
    }

    public List<UnitInstance> getAliveUnits() {
        return getAllUnits().stream()
            .filter(UnitInstance::isAlive)
            .sorted(Comparator.comparing(UnitInstance::getId))
            .collect(Collectors.toList());
    }

    public List<UnitInstance> getAliveUnitsForPlayer(int playerId) {
        return getAliveUnits().stream()
            .filter(unit -> unit.getPlayerId() == playerId)
            .collect(Collectors.toList());
    }

    public UnitInstance getUnit(String unitId) {
        return unitMap.get(unitId);
    }

    public Integer getPlayerIdForUnit(String unitId) {
        return unitPlayerIds.get(unitId);
    }

    public boolean isOccupied(int x, int y) {
        return unitMap.values().stream()
            .anyMatch(unit -> unit.getX() == x && unit.getY() == y && unit.isAlive());
    }

    public UnitInstance getUnitAt(int x, int y) {
        return unitMap.values().stream()
            .filter(unit -> unit.getX() == x && unit.getY() == y && unit.isAlive())
            .findFirst()
            .orElse(null);
    }

    public void removeDead() {
        List<String> deadIds = unitMap.values().stream()
            .filter(unit -> !unit.isAlive())
            .map(UnitInstance::getId)
            .toList();
        for (String unitId : deadIds) {
            removeUnit(unitId);
        }
    }

    public boolean isEmptyForPlayer(int playerId) {
        return getAliveUnitsForPlayer(playerId).isEmpty();
    }

    public int getUnitCountForPlayer(int playerId) {
        return getAliveUnitsForPlayer(playerId).size();
    }

    public int getTotalHpForPlayer(int playerId) {
        return getAliveUnitsForPlayer(playerId).stream()
            .mapToInt(UnitInstance::getCurrentHp)
            .sum();
    }

    public static boolean isValidPosition(int x, int y) {
        return Coordinates.isValidCombat(x, y);
    }

    @Override
    public String toString() {
        return String.format("CombatBoard(units:%d)", getAliveUnits().size());
    }
}
