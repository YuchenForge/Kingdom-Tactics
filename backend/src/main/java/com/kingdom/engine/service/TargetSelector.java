package com.kingdom.engine.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.kingdom.engine.domain.Board;
import com.kingdom.engine.domain.UnitInstance;

/**
 * Pure utility for selecting combat targets.
 * All targeting rules and tie-breakers are centralized here.
 */
public class TargetSelector {
    private TargetSelector() {
        // Utility class - prevent instantiation
    }

    /**
     * Select target for a unit based on its type and special ability.
     * Tie-breaking order: distance, HP, unit ID.
     */
    public static UnitInstance selectTarget(UnitInstance unit, Board enemyBoard) {
        List<UnitInstance> enemies = new ArrayList<>(enemyBoard.getAliveUnits());

        if (enemies.isEmpty()) {
            return null;
        }

        String unitType = unit.getType();

        if ("Ranger".equals(unitType)
                || "Mage".equals(unitType)
                || "Healer".equals(unitType)) {

            enemies.sort(
                Comparator.comparingInt(UnitInstance::getCurrentHp) // 1. Lowest HP
                    .thenComparingInt(enemy -> unit.manhattanDistance(enemy)) // 2. Distance
                    .thenComparing(UnitInstance::getId) // 3. ID tie-break
            );
        } else {
            enemies.sort(
                Comparator.<UnitInstance>comparingInt(enemy -> unit.manhattanDistance(enemy)) // 1. Distance
                    .thenComparingInt(UnitInstance::getCurrentHp) // 2. Lowest HP
                    .thenComparing(UnitInstance::getId) // 3. ID tie-break
            );
        }

        return enemies.get(0);
    }

    /**
     * Find lowest HP ally in ownBoard within range for unit
     */
    public static UnitInstance findLowestHpAllyInRange(UnitInstance unit, Board ownBoard, int range) {
        List<UnitInstance> allies = new ArrayList<>();
        allies.addAll(ownBoard.getAliveUnits());
        allies.sort(Comparator.comparingInt(UnitInstance::getCurrentHp)
            .thenComparing(UnitInstance::getId));
        
        for (UnitInstance ally: allies) {
            int distance = unit.chebyshevDistance(ally);
            if (distance <= range) return ally;
        }

        return null;
    }
}
