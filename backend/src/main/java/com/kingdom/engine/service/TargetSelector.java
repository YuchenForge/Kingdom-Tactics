package com.kingdom.engine.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

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

        if ("Ranger".equals(unitType)) {
            List<UnitInstance> inRange = candidates.stream()
                .filter(enemy -> unit.chebyshevDistance(enemy) <= unit.getRange())
                .collect(Collectors.toList());
            if (!inRange.isEmpty()) {
                return pickLowestHpFirst(unit, inRange);
            }
            return pickLowestHpFirst(unit, candidates);
        }

        if ("Mage".equals(unitType) || "Healer".equals(unitType)) {
            return pickLowestHpFirst(unit, candidates);
        }

        return pickNearestFirst(unit, candidates);
    }

    public static UnitInstance findLowestHpAlly(UnitInstance unit, List<UnitInstance> allies) {
        if (allies.isEmpty()) {
            return null;
        }
        List<UnitInstance> sortedAllies = new ArrayList<>(allies);
        sortedAllies.sort(Comparator.comparingInt(UnitInstance::getCurrentHp)
            .thenComparing(UnitInstance::getId));
        return sortedAllies.get(0);
    }

    private static UnitInstance pickLowestHpFirst(UnitInstance unit, List<UnitInstance> candidates) {
        candidates.sort(
            Comparator.comparingInt(UnitInstance::getCurrentHp)
                .thenComparingInt(enemy -> unit.manhattanDistance(enemy))
                .thenComparing(UnitInstance::getId));
        return candidates.get(0);
    }

    private static UnitInstance pickNearestFirst(UnitInstance unit, List<UnitInstance> candidates) {
        candidates.sort(
            Comparator.<UnitInstance>comparingInt(enemy -> unit.manhattanDistance(enemy))
                .thenComparingInt(UnitInstance::getCurrentHp)
                .thenComparing(UnitInstance::getId));
        return candidates.get(0);
    }
}
