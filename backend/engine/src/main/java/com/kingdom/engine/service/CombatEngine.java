package com.kingdom.engine.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.kingdom.engine.domain.Board;
import com.kingdom.engine.domain.CombatBoard;
import com.kingdom.engine.domain.CombatEvent;
import com.kingdom.engine.domain.ResolutionResult;
import com.kingdom.engine.domain.UnitInstance;

/**
 * Deterministic combat engine.
 *
 * <p>Accepts two per-player placement boards, merges them into a 4×8 combat board,
 * then runs the tick loop on global coordinates.
 */
public class CombatEngine {
    private static final int MAX_TICKS = 160;

    public CombatEngine(long seed) {
        // Seed is part of the public API for replay/CLI contract; combat is fully deterministic without RNG.
    }

    /**
     * Resolve combat between two placement boards.
     */
    public ResolutionResult resolve(Board playerBoard, Board enemyBoard) {
        CombatBoard combatBoard = CombatBoard.merge(playerBoard, enemyBoard);
        return resolveCombatBoard(combatBoard);
    }

    private ResolutionResult resolveCombatBoard(CombatBoard combatBoard) {
        List<CombatEvent> events = new ArrayList<>();

        int tick = 0;
        for (UnitInstance unit : combatBoard.getAliveUnits()) {
            events.add(CombatEvent.unitPlaced(tick, unit));
        }

        boolean combatEnded = false;
        for (tick = 0; tick < MAX_TICKS; tick++) {
            if (combatBoard.isEmptyForPlayer(0) || combatBoard.isEmptyForPlayer(1)) {
                events.add(CombatEvent.combatEnded(tick, determineEndReason(combatBoard)));
                combatEnded = true;
                break;
            }

            for (UnitInstance unit : combatBoard.getAliveUnits()) {
                unit.decrementCooldown();
            }

            List<UnitInstance> allUnits = new ArrayList<>(combatBoard.getAliveUnits());
            allUnits.sort(Comparator.comparing(UnitInstance::getId));

            Set<String> scheduledActors = new HashSet<>();
            for (UnitInstance unit : allUnits) {
                if (unit.isAlive() && unit.canAct()) {
                    scheduledActors.add(unit.getId());
                }
            }

            for (UnitInstance unit : allUnits) {
                if (!scheduledActors.contains(unit.getId())) {
                    continue;
                }

                int playerId = unit.getPlayerId();
                int enemyPlayerId = playerId == 0 ? 1 : 0;
                List<UnitInstance> enemies = combatBoard.getAliveUnitsForPlayer(enemyPlayerId);
                List<UnitInstance> allies = combatBoard.getAliveUnitsForPlayer(playerId);

                UnitInstance target = TargetSelector.selectTarget(unit, enemies);

                if ("Healer".equals(unit.getType()) && unit.willTriggerSpecialOnNextAttack()) {
                    handleHealerHeal(unit, allies, events, tick);
                } else if (target != null && unit.chebyshevDistance(target) <= unit.getRange()) {
                    handleAttack(unit, target, combatBoard, events, tick);
                } else if (target != null && unit.isAlive()) {
                    moveAlongPlannedPath(unit, target, combatBoard, events, tick);
                }

                unit.resetCooldown();
            }

            combatBoard.removeDead();
        }

        String endReason = determineEndReason(combatBoard);
        if (!combatEnded && "TIME_LIMIT".equals(endReason)) {
            events.add(CombatEvent.combatEnded(tick, endReason));
        }

        int winnerPlayerId = determineWinner(combatBoard);
        int[] keepDamageByPlayer = ResolutionResult.calculateKeepDamageByPlayer(combatBoard, winnerPlayerId);

        return new ResolutionResult(
            events, combatBoard, keepDamageByPlayer, tick, endReason, winnerPlayerId);
    }

    private static String determineEndReason(CombatBoard combatBoard) {
        boolean empty0 = combatBoard.isEmptyForPlayer(0);
        boolean empty1 = combatBoard.isEmptyForPlayer(1);
        if (empty0 && empty1) {
            return "DRAW";
        }
        if (empty0) {
            return "ENEMY_VICTORY";
        }
        if (empty1) {
            return "PLAYER_VICTORY";
        }
        return "TIME_LIMIT";
    }

    /**
     * Player with surviving units wins by elimination; mutual wipe is a draw; on time limit, higher total HP wins.
     */
    static int determineWinner(CombatBoard combatBoard) {
        boolean empty0 = combatBoard.isEmptyForPlayer(0);
        boolean empty1 = combatBoard.isEmptyForPlayer(1);

        if (empty0 && empty1) {
            return -1;
        }
        if (empty0) {
            return 1;
        }
        if (empty1) {
            return 0;
        }

        int hp0 = combatBoard.getTotalHpForPlayer(0);
        int hp1 = combatBoard.getTotalHpForPlayer(1);
        if (hp0 != hp1) {
            return hp0 > hp1 ? 0 : 1;
        }

        int count0 = combatBoard.getUnitCountForPlayer(0);
        int count1 = combatBoard.getUnitCountForPlayer(1);
        if (count0 != count1) {
            return count0 > count1 ? 0 : 1;
        }

        return 0;
    }

    /**
     * Move one orthogonal tile along the BFS shortest path toward a reachable attack
     * position, or toward the closest reachable tile when no attack position exists.
     */
    private static void moveAlongPlannedPath(
            UnitInstance unit,
            UnitInstance target,
            CombatBoard combatBoard,
            List<CombatEvent> events,
            int tick) {
        MovementPlanner.computeNextStep(unit, target, combatBoard).ifPresent(next -> {
            unit.setPosition(next[0], next[1]);
            events.add(CombatEvent.unitMoved(
                tick, unit.getId(), unit.getX(), unit.getY(), unit.getPlayerId()));
        });
    }

    private void handleHealerHeal(
            UnitInstance healer,
            List<UnitInstance> allies,
            List<CombatEvent> events,
            int tick) {
        healer.incrementActionCounter();
        UnitInstance ally = TargetSelector.findLowestHpAlly(allies);
        if (ally != null) {
            int oldHp = ally.getCurrentHp();
            ally.heal(healer.getHealAmount());
            events.add(CombatEvent.healed(
                tick, healer.getId(), ally.getId(), ally.getCurrentHp() - oldHp));
        }
    }

    private void handleAttack(
            UnitInstance attacker,
            UnitInstance target,
            CombatBoard combatBoard,
            List<CombatEvent> events,
            int tick) {
        attacker.incrementActionCounter();

        int baseDamage = Math.max(1, attacker.getAttack() - target.getArmor());
        target.takeDamage(baseDamage);
        events.add(CombatEvent.attack(tick, attacker.getId(), target.getId(), baseDamage));

        if (!target.isAlive()) {
            events.add(CombatEvent.unitDied(tick, target.getId()));
        }

        if ("Mage".equals(attacker.getType()) && attacker.isTriggerSpecialAction()) {
            int[][] directions = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
            for (int[] dir : directions) {
                int splashX = attacker.getX() + dir[0];
                int splashY = attacker.getY() + dir[1];

                if (!CombatBoard.isValidPosition(splashX, splashY)) {
                    continue;
                }

                UnitInstance neighbor = combatBoard.getUnitAt(splashX, splashY);
                if (neighbor != null
                        && !neighbor.equals(target)
                        && !neighbor.getPlayerId().equals(attacker.getPlayerId())) {
                    neighbor.takeDamage(attacker.getAttack());
                    events.add(CombatEvent.attack(
                        tick, attacker.getId(), neighbor.getId(), attacker.getAttack()));
                    if (!neighbor.isAlive()) {
                        events.add(CombatEvent.unitDied(tick, neighbor.getId()));
                    }
                }
            }
        }
    }
}
