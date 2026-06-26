package com.kingdom.engine;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

import com.kingdom.engine.domain.Board;
import com.kingdom.engine.domain.ResolutionResult;
import com.kingdom.engine.domain.UnitDefinition;
import com.kingdom.engine.domain.UnitInstance;
import com.kingdom.engine.service.CombatEngine;

/**
 * Test Scenario 1: Basic melee attack (Squire vs Squire)
 * 
 * Setup:
 * - P1 board: Squire #1 (HP=8, ATK=2, RNG=1)
 * - P2 board: Squire #2 (HP=8, ATK=2, RNG=1)
 * - Both units are 3 tiles apart
 * 
 * Expected behavior:
 * - Both units move toward each other
 * - After 3 ticks: adjacent
 * - Both attack until both die (simultaneous death)
 */
class CombatEngineTest {

    @Test
    void scenario_1_squire_vs_squire() {
        // Setup
        Board playerBoard = new Board(0);
        Board enemyBoard = new Board(1);

        UnitInstance squire1 = new UnitInstance("unit_001", UnitDefinition.squire(), 0, 2);
        UnitInstance squire2 = new UnitInstance("unit_002", UnitDefinition.squire(), 3, 2);

        playerBoard.addUnit(squire1);
        enemyBoard.addUnit(squire2);

        // Run combat
        CombatEngine engine = new CombatEngine(12345L);
        ResolutionResult result = engine.resolve(playerBoard, enemyBoard);

        // Verify: both units should be dead (simultaneous)
        assertThat(result.getEvents()).isNotEmpty();
        assertThat(result.getKeepDamage()).isEqualTo(2);  
        
        System.out.println("Scenario 1 passed:");
        System.out.println("  Events: " + result.getEvents().size());
        System.out.println("  Keep damage: " + result.getKeepDamage());
        System.out.println("  End reason: " + result.getEndReason());
    }

    @Test
    void scenario_2_knight_vs_shieldbearer() {
        // Setup
        Board playerBoard = new Board(0);
        Board enemyBoard = new Board(1);

        UnitInstance knight = new UnitInstance("unit_001", UnitDefinition.knight(), 0, 0);
        UnitInstance shieldbearer = new UnitInstance("unit_002", UnitDefinition.shieldbearer(), 3, 0);

        playerBoard.addUnit(knight);
        enemyBoard.addUnit(shieldbearer);

        // Run combat
        CombatEngine engine = new CombatEngine(54321L);
        ResolutionResult result = engine.resolve(playerBoard, enemyBoard);

        // Knight should win (higher ATK, less armor reduction)
        assertThat(result.getEvents()).isNotEmpty();
        assertThat(result.getKeepDamage()).isGreaterThan(0);
        
        System.out.println("Scenario 2 passed:");
        System.out.println("  Events: " + result.getEvents().size());
        System.out.println("  Keep damage: " + result.getKeepDamage());
    }

    @Test
    void determinism_same_seed_same_output() {
        // Create two identical boards
        Board board1P = new Board(0);
        Board board1E = new Board(1);
        Board board2P = new Board(0);
        Board board2E = new Board(1);

        board1P.addUnit(new UnitInstance("unit_001", UnitDefinition.squire(), 0, 0));
        board1E.addUnit(new UnitInstance("unit_002", UnitDefinition.squire(), 3, 3));

        board2P.addUnit(new UnitInstance("unit_001", UnitDefinition.squire(), 0, 0));
        board2E.addUnit(new UnitInstance("unit_002", UnitDefinition.squire(), 3, 3));

        // Run with same seed
        long seed = 99999L;
        CombatEngine engine1 = new CombatEngine(seed);
        CombatEngine engine2 = new CombatEngine(seed);

        ResolutionResult result1 = engine1.resolve(board1P, board1E);
        ResolutionResult result2 = engine2.resolve(board2P, board2E);

        // Should be identical
        assertThat(result1.getEvents().size()).isEqualTo(result2.getEvents().size());
        assertThat(result1.getKeepDamage()).isEqualTo(result2.getKeepDamage());
        assertThat(result1.getEndReason()).isEqualTo(result2.getEndReason());

        System.out.println("Determinism test passed:");
        System.out.println("  Result 1: " + result1);
        System.out.println("  Result 2: " + result2);
    }
}
