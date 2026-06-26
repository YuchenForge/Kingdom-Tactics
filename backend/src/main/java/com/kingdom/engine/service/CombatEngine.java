package com.kingdom.engine.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import com.kingdom.engine.domain.Board;
import com.kingdom.engine.domain.CombatEvent;
import com.kingdom.engine.domain.ResolutionResult;
import com.kingdom.engine.domain.UnitInstance;

/**
 * Deterministic combat engine.
 * 
 * Rules:
 * - 250ms logical tick (4 ticks/second)
 * - Max 160 ticks (40 seconds)
 * - Units act in ID order (deterministic)
 * - Cooldown starts at 4 ticks = 1 second
 * - All RNG is seeded (deterministic)
 */
public class CombatEngine {
    private static final int MAX_TICKS = 160;
    private static final int TICK_DURATION_MS = 250;
    private static final int MAX_COMBAT_SECONDS = 40;

    private final long seed;
    private final Random rng;

    public CombatEngine(long seed) {
        this.seed = seed;
        this.rng = new Random(seed);
    }

    /**
     * Resolve combat between two boards.
     * Returns the result of the simulation.
     */
    public ResolutionResult resolve(Board playerBoard, Board enemyBoard) {
        List<CombatEvent> events = new ArrayList<>();
        
        // Place all units at tick 0
        int tick = 0;
        for (UnitInstance unit : playerBoard.getAliveUnits()) {
            events.add(CombatEvent.unitPlaced(tick, unit.getId(), unit.getType(), unit.getX(), unit.getY()));
        }
        for (UnitInstance unit : enemyBoard.getAliveUnits()) {
            events.add(CombatEvent.unitPlaced(tick, unit.getId(), unit.getType(), unit.getX(), unit.getY()));
        }

        // Main tick loop
        for (tick = 0; tick < MAX_TICKS; tick++) {
            // Check if either side has no units (immediate loss)
            if (playerBoard.isEmpty() || enemyBoard.isEmpty()) {
                String reason = playerBoard.isEmpty() ? "ENEMY_WINS" : "PLAYER_WINS";
                events.add(CombatEvent.combatEnded(tick, reason));
                break;
            }

            // Decrement cooldowns for all units
            decrementCooldowns(playerBoard);
            decrementCooldowns(enemyBoard);

            // Act phase: each unit tries to act (in deterministic ID order)
            List<UnitInstance> allUnits = new ArrayList<>();
            allUnits.addAll(playerBoard.getAliveUnits());
            allUnits.addAll(enemyBoard.getAliveUnits());
            allUnits.sort(Comparator.comparing(UnitInstance::getId));

            for (UnitInstance unit : allUnits) {
                if (!unit.isAlive() || !unit.canAct()) continue;

                // Determine which board this unit belongs to
                Board ownBoard = containsUnit(playerBoard, unit) ? playerBoard : enemyBoard;
                Board enemyBoardRef = containsUnit(playerBoard, unit) ? enemyBoard : playerBoard;

                // Select target
                UnitInstance target = selectTarget(unit, enemyBoardRef);

                if (target != null && unit.chebyshevDistance(target) <= unit.getRange()) {
                    // Attack
                    handleAttack(unit, target, enemyBoardRef, events, tick);
                } else if (target != null) {
                    // Move toward target
                    unit.moveToward(target.getX(), target.getY());
                    events.add(CombatEvent.unitMoved(tick, unit.getId(), unit.getX(), unit.getY()));
                }

                unit.resetCooldown();
            }

            // Cleanup: remove dead units
            playerBoard.removeDead();
            enemyBoard.removeDead();
        }

        // Determine winner and calculate Keep damage
        Board winnerBoard = playerBoard.isEmpty() ? enemyBoard : playerBoard;
        int keepDamage = ResolutionResult.calculateKeepDamage(winnerBoard);

        String endReason = playerBoard.isEmpty() ? "ENEMY_VICTORY" : 
                          enemyBoard.isEmpty() ? "PLAYER_VICTORY" : 
                          "TIME_LIMIT";

        return new ResolutionResult(events, winnerBoard, keepDamage, tick, endReason);
    }

    /**
     * Decrement cooldowns for all alive units on a board.
     */
    private void decrementCooldowns(Board board) {
        for (UnitInstance unit : board.getAliveUnits()) {
            unit.decrementCooldown();
        }
    }

    /**
     * Check if a board contains a specific unit.
     */
    private boolean containsUnit(Board board, UnitInstance unit) {
        return board.getUnit(unit.getId()) != null;
    }

    /**
     * Select target for a unit based on its type and special ability.
     * Tie-breaking order: distance, HP, unit ID.
     */
    private UnitInstance selectTarget(UnitInstance unit, Board enemyBoard) {
        List<UnitInstance> enemies = new ArrayList<>(enemyBoard.getAliveUnits());

        if (enemies.isEmpty()) {
            return null;
        }

        String unitType = unit.getType();

        if ("Ranger".equals(unitType)
                || "Mage".equals(unitType)
                || "Healer".equals(unitType)) {

            enemies.sort(
                Comparator.comparingInt(UnitInstance::getCurrentHp)
                    .thenComparingInt(enemy -> unit.manhattanDistance(enemy))
                    .thenComparing(UnitInstance::getId)
            );
        } else {
            enemies.sort(
                Comparator.<UnitInstance>comparingInt(enemy -> unit.manhattanDistance(enemy))
                    .thenComparingInt(UnitInstance::getCurrentHp)
                    .thenComparing(UnitInstance::getId)
            );
        }

        return enemies.get(0);
    }

    /**
     * Handle attack between attacker and target.
     * Includes splash damage for Mage.
     */
    private void handleAttack(UnitInstance attacker, UnitInstance target, Board enemyBoard, List<CombatEvent> events, int tick) {
        attacker.incrementActionCounter();

        // Calculate damage
        int baseDamage = Math.max(1, attacker.getAttack() - target.getArmor());

        // Normal attack
        target.takeDamage(baseDamage);
        events.add(CombatEvent.attack(tick, attacker.getId(), target.getId(), baseDamage));

        if (!target.isAlive()) {
            events.add(CombatEvent.unitDied(tick, target.getId()));
        }

        // Mage special: every 3rd action is splash
        if ("Mage".equals(attacker.getType()) && attacker.isTriggerSpecialAction()) {
            // Splash damage to orthogonal neighbors (ignores armor)
            int[][] directions = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
            for (int[] dir : directions) {
                int splashX = attacker.getX() + dir[0];
                int splashY = attacker.getY() + dir[1];
                
                if (!Board.isValidPosition(splashX, splashY)) continue;
                
                UnitInstance neighbor = enemyBoard.getUnitAt(splashX, splashY);
                if (neighbor != null && !neighbor.equals(target)) {
                    neighbor.takeDamage(attacker.getAttack());  // Splash ignores armor
                    events.add(CombatEvent.attack(tick, attacker.getId(), neighbor.getId(), attacker.getAttack()));
                    if (!neighbor.isAlive()) {
                        events.add(CombatEvent.unitDied(tick, neighbor.getId()));
                    }
                }
            }
        }
    }
}