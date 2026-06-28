package com.kingdom.engine.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

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

    private final long seed;
    private final Random rng;

    public CombatEngine(long seed) {
        this.seed = seed;
        this.rng = new Random(seed);
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
            events.add(CombatEvent.unitPlaced(
                tick, unit.getId(), unit.getType(), unit.getX(), unit.getY(), unit.getPlayerId()));
        }

        for (tick = 0; tick < MAX_TICKS; tick++) {
            if (combatBoard.isEmptyForPlayer(0) || combatBoard.isEmptyForPlayer(1)) {
                String reason = combatBoard.isEmptyForPlayer(0) ? "ENEMY_WINS" : "PLAYER_WINS";
                events.add(CombatEvent.combatEnded(tick, reason));
                break;
            }

            for (UnitInstance unit : combatBoard.getAliveUnits()) {
                unit.decrementCooldown();
            }

            List<UnitInstance> allUnits = new ArrayList<>(combatBoard.getAliveUnits());
            allUnits.sort(Comparator.comparing(UnitInstance::getId));

            for (UnitInstance unit : allUnits) {
                if (!unit.isAlive() || !unit.canAct()) {
                    continue;
                }

                int playerId = unit.getPlayerId();
                int enemyPlayerId = playerId == 0 ? 1 : 0;
                List<UnitInstance> enemies = combatBoard.getAliveUnitsForPlayer(enemyPlayerId);
                List<UnitInstance> allies = combatBoard.getAliveUnitsForPlayer(playerId);

                UnitInstance target = TargetSelector.selectTarget(unit, enemies);

                if (target != null && unit.chebyshevDistance(target) <= unit.getRange()) {
                    handleAttack(unit, target, combatBoard, allies, events, tick);
                } else if (target != null) {
                    int nextX = unit.getX();
                    int nextY = unit.getY();

                    if (unit.getX() < target.getX()) {
                        nextX++;
                    } else if (unit.getX() > target.getX()) {
                        nextX--;
                    }

                    if (unit.getY() < target.getY()) {
                        nextY++;
                    } else if (unit.getY() > target.getY()) {
                        nextY--;
                    }

                    if (!combatBoard.isOccupied(nextX, nextY)
                            && CombatBoard.isValidPosition(nextX, nextY)) {
                        unit.setPosition(nextX, nextY);
                        events.add(CombatEvent.unitMoved(
                            tick, unit.getId(), unit.getX(), unit.getY(), unit.getPlayerId()));
                    }
                }

                unit.resetCooldown();
            }

            combatBoard.removeDead();
        }

        String endReason = determineEndReason(combatBoard);
        if ("TIME_LIMIT".equals(endReason)) {
            events.add(CombatEvent.combatEnded(tick, endReason));
        }

        int winnerPlayerId = determineWinner(combatBoard);
        int keepDamage = ResolutionResult.calculateKeepDamage(combatBoard, winnerPlayerId);

        return new ResolutionResult(events, combatBoard, keepDamage, tick, endReason, winnerPlayerId);
    }

    private static String determineEndReason(CombatBoard combatBoard) {
        if (combatBoard.isEmptyForPlayer(0)) {
            return "ENEMY_VICTORY";
        }
        if (combatBoard.isEmptyForPlayer(1)) {
            return "PLAYER_VICTORY";
        }
        return "TIME_LIMIT";
    }

    /**
     * Player with surviving units wins by elimination; on time limit, higher total HP wins.
     */
    static int determineWinner(CombatBoard combatBoard) {
        boolean empty0 = combatBoard.isEmptyForPlayer(0);
        boolean empty1 = combatBoard.isEmptyForPlayer(1);

        if (empty0 && !empty1) {
            return 1;
        }
        if (empty1 && !empty0) {
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

    private void handleAttack(
            UnitInstance attacker,
            UnitInstance target,
            CombatBoard combatBoard,
            List<UnitInstance> allies,
            List<CombatEvent> events,
            int tick) {
        attacker.incrementActionCounter();

        if ("Healer".equals(attacker.getType()) && attacker.isTriggerSpecialAction()) {
            UnitInstance ally = TargetSelector.findLowestHpAllyInRange(attacker, allies, 2);
            if (ally != null) {
                int oldHp = ally.getCurrentHp();
                ally.heal(5);
                events.add(CombatEvent.healed(
                    tick, attacker.getId(), ally.getId(), ally.getCurrentHp() - oldHp));
            }
            return;
        }

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
