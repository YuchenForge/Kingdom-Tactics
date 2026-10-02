package com.kingdom.engine.service;

import java.util.ArrayList;
import java.util.List;

import com.kingdom.engine.domain.Board;
import com.kingdom.engine.domain.CombatBoard;
import com.kingdom.engine.domain.CombatEvent;
import com.kingdom.engine.domain.CombatOutcome;
import com.kingdom.engine.domain.ResolutionResult;
import com.kingdom.engine.domain.UnitInstance;

/**
 * Deterministic combat engine.
 *
 * Accepts two per-player placement boards, merges them into a 4×8 combat board,
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
        return resolve(playerBoard, enemyBoard, MAX_TICKS);
    }

    /**
     * Same as {@link #resolve(Board, Board)} with a custom tick budget.
     * Used to test endings that land on the last permitted tick.
     */
    public ResolutionResult resolve(Board playerBoard, Board enemyBoard, int maxTicks) {
        if (maxTicks < 1) {
            throw new IllegalArgumentException("maxTicks must be >= 1: " + maxTicks);
        }
        CombatBoard combatBoard = CombatBoard.merge(playerBoard, enemyBoard);
        return resolveCombatBoard(combatBoard, maxTicks);
    }

    private ResolutionResult resolveCombatBoard(CombatBoard combatBoard, int maxTicks) {
        List<CombatEvent> events = new ArrayList<>();

        int tick = 0;
        for (UnitInstance unit : combatBoard.getAliveUnits()) {
            events.add(CombatEvent.unitPlaced(tick, unit));
        }

        for (tick = 0; tick < maxTicks; tick++) {
            if (combatBoard.isEmptyForPlayer(0) || combatBoard.isEmptyForPlayer(1)) {
                break;
            }

            for (UnitInstance unit : combatBoard.getAliveUnits()) {
                unit.decrementCooldown();
            }

            // Snapshot ready actors in ID order at tick start; they may still act after dying mid-tick.
            List<UnitInstance> actors = new ArrayList<>();
            for (UnitInstance unit : combatBoard.getAliveUnits()) {
                if (unit.canAct()) {
                    actors.add(unit);
                }
            }

            for (UnitInstance unit : actors) {
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

        CombatOutcome endReason = determineEndReason(combatBoard);
        events.add(CombatEvent.combatEnded(tick, endReason.name()));

        int winnerPlayerId = determineWinner(combatBoard);
        int[] keepDamageByPlayer = ResolutionResult.calculateKeepDamageByPlayer(combatBoard, winnerPlayerId);

        return new ResolutionResult(
            events, combatBoard, keepDamageByPlayer, tick, endReason.name(), winnerPlayerId);
    }

    private static CombatOutcome determineEndReason(CombatBoard combatBoard) {
        boolean empty0 = combatBoard.isEmptyForPlayer(0);
        boolean empty1 = combatBoard.isEmptyForPlayer(1);
        if (empty0 && empty1) {
            return CombatOutcome.DRAW;
        }
        if (empty0) {
            return CombatOutcome.ENEMY_VICTORY;
        }
        if (empty1) {
            return CombatOutcome.PLAYER_VICTORY;
        }
        return CombatOutcome.TIME_LIMIT;
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
                // Splash surrounds the impact tile, even if the direct hit killed its target.
                int splashX = target.getX() + dir[0];
                int splashY = target.getY() + dir[1];

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
