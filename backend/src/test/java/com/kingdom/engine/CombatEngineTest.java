package com.kingdom.engine;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

import com.kingdom.engine.domain.Board;
import com.kingdom.engine.domain.CombatEvent;
import com.kingdom.engine.domain.ResolutionResult;
import com.kingdom.engine.domain.UnitDefinition;
import com.kingdom.engine.domain.UnitInstance;
import com.kingdom.engine.service.CombatEngine;

class CombatEngineTest {

    @Test
    void scenario_1_squire_vs_squire() {
        Board playerBoard = new Board(0);
        Board enemyBoard = new Board(1);

        // Local (2,0) → combat (1,3); P1 local (1,2) → combat (1,6); 3 tiles apart
        UnitInstance squire1 = new UnitInstance("unit_001", UnitDefinition.squire(), 2, 0);
        UnitInstance squire2 = new UnitInstance("unit_002", UnitDefinition.squire(), 1, 2);

        playerBoard.addUnit(squire1);
        enemyBoard.addUnit(squire2);

        CombatEngine engine = new CombatEngine(12345L);
        ResolutionResult result = engine.resolve(playerBoard, enemyBoard);

        assertThat(result.getEvents()).isNotEmpty();
        assertThat(result.getWinnerPlayerId()).isEqualTo(-1);
        assertThat(result.getKeepDamageForPlayer(0)).isEqualTo(1);
        assertThat(result.getKeepDamageForPlayer(1)).isEqualTo(1);
        assertThat(result.getEndReason()).isEqualTo("DRAW");
        assertThat(result.getFinalBoard().getUnitCountForPlayer(0)).isZero();
        assertThat(result.getFinalBoard().getUnitCountForPlayer(1)).isZero();

        // Verify events exist
        List<CombatEvent.EventType> eventTypes = result.getEvents().stream()
            .map(CombatEvent::getType)
            .collect(Collectors.toList());
        assertThat(eventTypes).contains(
            CombatEvent.EventType.UNIT_MOVED,
            CombatEvent.EventType.ATTACK,
            CombatEvent.EventType.UNIT_DIED
        );
    }

    @Test
    void scenario_2_knight_vs_shieldbearer() {
        Board playerBoard = new Board(0);
        Board enemyBoard = new Board(1);

        // Local (2,3) → combat (1,0); P1 local (0,0) → combat (0,4)
        UnitInstance knight = new UnitInstance("unit_001", UnitDefinition.knight(), 2, 3);
        UnitInstance shieldbearer = new UnitInstance("unit_002", UnitDefinition.shieldbearer(), 0, 0);

        playerBoard.addUnit(knight);
        enemyBoard.addUnit(shieldbearer);

        CombatEngine engine = new CombatEngine(54321L);
        ResolutionResult result = engine.resolve(playerBoard, enemyBoard);

        // Knight should win
        assertThat(result.getEvents()).isNotEmpty();
        assertThat(result.getKeepDamageForPlayer(0)).isZero();
        assertThat(result.getKeepDamageForPlayer(1)).isEqualTo(3);
        assertThat(result.getWinnerPlayerId()).isEqualTo(0);

        // Count attack events (should be multiple)
        long attackEvents = result.getEvents().stream()
            .filter(e -> e.getType() == CombatEvent.EventType.ATTACK)
            .count();
        assertThat(attackEvents).isGreaterThan(4);
    }

    @Test
    void scenario_3_ranger_target_lowest_HP() {
        Board playerBoard = new Board(0);
        Board enemyBoard = new Board(1);

        // Ranger stays on back row; both enemies are within Chebyshev range 3
        // Local (2,0) → combat (1,3); enemies on P1 local (0,2) and (1,2) → combat (0,6) and (1,6)
        UnitInstance ranger = new UnitInstance("unit_001", UnitDefinition.ranger(), 2, 0);
        UnitInstance highHpEnemy = new UnitInstance("unit_002", UnitDefinition.shieldbearer(), 0, 2);
        UnitInstance lowHpEnemy = new UnitInstance("unit_003", UnitDefinition.squire(), 1, 2);

        playerBoard.addUnit(ranger);
        enemyBoard.addUnit(highHpEnemy);
        enemyBoard.addUnit(lowHpEnemy);

        CombatEngine engine = new CombatEngine(54321L);
        ResolutionResult result = engine.resolve(playerBoard, enemyBoard);

        // Ranger targets squire first
        List<CombatEvent> rangerAttacks = result.getEvents().stream()
            .filter(e -> e.getType() == CombatEvent.EventType.ATTACK)
            .filter(e -> "unit_001".equals(e.getData().get("attackerId")))
            .collect(Collectors.toList());

        assertThat(rangerAttacks).isNotEmpty();
        assertThat(rangerAttacks.get(0).getData().get("targetId")).isEqualTo("unit_003");

        assertThat(result.getWinnerPlayerId()).isEqualTo(0);
        assertThat(result.getKeepDamageForPlayer(0)).isZero();
        assertThat(result.getKeepDamageForPlayer(1)).isEqualTo(2);
    }

    @Test
    void scenario_4_knight_target_nearest_enemy() {
        Board playerBoard = new Board(0);
        Board enemyBoard = new Board(1);

        // Local (3,3) → combat (0,0); P1 near (0,2) → (0,6), far (1,2) → (1,6)
        UnitInstance knight = new UnitInstance("unit_001", UnitDefinition.knight(), 3, 3);
        UnitInstance nearEnemy = new UnitInstance("unit_002", UnitDefinition.squire(), 0, 2);
        UnitInstance farEnemy = new UnitInstance("unit_003", UnitDefinition.squire(), 1, 2);

        playerBoard.addUnit(knight);
        enemyBoard.addUnit(nearEnemy);
        enemyBoard.addUnit(farEnemy);

        CombatEngine engine = new CombatEngine(54321L);
        ResolutionResult result = engine.resolve(playerBoard, enemyBoard);

        List<CombatEvent> knightAttacks = result.getEvents().stream()
            .filter(e -> e.getType() == CombatEvent.EventType.ATTACK)
            .filter(e -> "unit_001".equals(e.getData().get("attackerId")))
            .collect(Collectors.toList());

        assertThat(knightAttacks).isNotEmpty();
        assertThat(knightAttacks.get(0).getData().get("targetId")).isEqualTo("unit_002");

        assertThat(result.getWinnerPlayerId()).isEqualTo(0);
        assertThat(result.getKeepDamageForPlayer(0)).isZero();
        assertThat(result.getKeepDamageForPlayer(1)).isEqualTo(2);
    }

    @Test
    void determinism_same_seed_same_output() {
        Board board1P = new Board(0);
        Board board1E = new Board(1);
        Board board2P = new Board(0);
        Board board2E = new Board(1);

        board1P.addUnit(new UnitInstance("unit_001", UnitDefinition.squire(), 3, 3));
        board1E.addUnit(new UnitInstance("unit_002", UnitDefinition.squire(), 0, 0));

        board2P.addUnit(new UnitInstance("unit_001", UnitDefinition.squire(), 3, 3));
        board2E.addUnit(new UnitInstance("unit_002", UnitDefinition.squire(), 0, 0));

        long seed = 99999L;
        CombatEngine engine1 = new CombatEngine(seed);
        CombatEngine engine2 = new CombatEngine(seed);

        ResolutionResult result1 = engine1.resolve(board1P, board1E);
        ResolutionResult result2 = engine2.resolve(board2P, board2E);

        assertThat(result1.getEvents().size()).isEqualTo(result2.getEvents().size());
        assertThat(result1.getKeepDamageForPlayer(0)).isEqualTo(result2.getKeepDamageForPlayer(0));
        assertThat(result1.getKeepDamageForPlayer(1)).isEqualTo(result2.getKeepDamageForPlayer(1));
        assertThat(result1.getEndReason()).isEqualTo(result2.getEndReason());
    }
}
