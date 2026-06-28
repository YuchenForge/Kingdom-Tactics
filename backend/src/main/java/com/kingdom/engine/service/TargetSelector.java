package com.kingdom.engine.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.kingdom.engine.domain.UnitInstance;

/**
 * Pure utility for selecting combat targets.
 */
public class TargetSelector {
    private TargetSelector() {
    }

  public static UnitInstance selectTarget(UnitInstance unit, List<UnitInstance> enemies) {
        List<UnitInstance> candidates = new ArrayList<>(enemies);

        if (candidates.isEmpty()) {
            return null;
        }

        String unitType = unit.getType();

        if ("Ranger".equals(unitType)
                || "Mage".equals(unitType)
                || "Healer".equals(unitType)) {
            candidates.sort(
                Comparator.comparingInt(UnitInstance::getCurrentHp)
                    .thenComparingInt(enemy -> unit.manhattanDistance(enemy))
                    .thenComparing(UnitInstance::getId));
        } else {
            candidates.sort(
                Comparator.<UnitInstance>comparingInt(enemy -> unit.manhattanDistance(enemy))
                    .thenComparingInt(UnitInstance::getCurrentHp)
                    .thenComparing(UnitInstance::getId));
        }

        return candidates.get(0);
    }

    public static UnitInstance findLowestHpAllyInRange(
            UnitInstance unit, List<UnitInstance> allies, int range) {
        List<UnitInstance> sortedAllies = new ArrayList<>(allies);
        sortedAllies.sort(Comparator.comparingInt(UnitInstance::getCurrentHp)
            .thenComparing(UnitInstance::getId));

        for (UnitInstance ally : sortedAllies) {
            if (unit.chebyshevDistance(ally) <= range) {
                return ally;
            }
        }

        return null;
    }
}
