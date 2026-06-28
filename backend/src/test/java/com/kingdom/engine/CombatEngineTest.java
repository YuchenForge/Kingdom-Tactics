package com.kingdom.engine;

import java.util.List;

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

        // Local placement: 3 tiles apart on merged board (P0 y=3, P1 local y=2 → combat y=6)
        UnitInstance squire1 = new UnitInstance("unit_001", UnitDefinition.squire(), 1, 3);
        UnitInstance squire2 = new UnitInstance("unit_002", UnitDefinition.squire(), 1, 2);

        playerBoard.addUnit(squire1);
        enemyBoard.addUnit(squire2);

        CombatEngine engine = new CombatEngine(12345L);
        ResolutionResult result = engine.resolve(playerBoard, enemyBoard);

        assertThat(result.getEvents()).isNotEmpty();
        assertThat(result.getKeepDamage()).isEqualTo(1);
        assertThat(result.getFinalBoard().getUnitCountForPlayer(0)).isZero();
        assertThat(result.getFinalBoard().getUnitCountForPlayer(1)).isZero();
    }

    @Test
    void scenario_2_knight_vs_shieldbearer() {
        Board playerBoard = new Board(0);
        Board enemyBoard = new Board(1);

        UnitInstance knight = new UnitInstance("unit_001", UnitDefinition.knight(), 1, 0);
        UnitInstance shieldbearer = new UnitInstance("unit_002", UnitDefinition.shieldbearer(), 0, 0);

        playerBoard.addUnit(knight);
        enemyBoard.addUnit(shieldbearer);

        CombatEngine engine = new CombatEngine(54321L);
        ResolutionResult result = engine.resolve(playerBoard, enemyBoard);

        assertThat(result.getEvents()).isNotEmpty();
        assertThat(result.getKeepDamage()).isGreaterThan(0);
        assertThat(result.getWinnerPlayerId()).isEqualTo(0);
    }

    @Test
    void scenario_3_ranger_target_lowest_HP() {
        Board playerBoard = new Board(0);
        Board enemyBoard = new Board(1);

        // Ranger stays on back row; both enemies are within Chebyshev range 3
        UnitInstance ranger = new UnitInstance("unit_001", UnitDefinition.ranger(), 1, 3);
        UnitInstance highHpEnemy = new UnitInstance("unit_002", UnitDefinition.shieldbearer(), 0, 2);
        UnitInstance lowHpEnemy = new UnitInstance("unit_003", UnitDefinition.squire(), 1, 2);

        playerBoard.addUnit(ranger);
        enemyBoard.addUnit(highHpEnemy);
        enemyBoard.addUnit(lowHpEnemy);

        CombatEngine engine = new CombatEngine(54321L);
        ResolutionResult result = engine.resolve(playerBoard, enemyBoard);

        List<CombatEvent> rangerAttacks = result.getEvents().stream()
            .filter(e -> e.getType() == CombatEvent.EventType.ATTACK)
            .filter(e -> "unit_001".equals(e.getData().get("attackerId")))
            .toList();

        assertThat(rangerAttacks).isNotEmpty();
        assertThat(rangerAttacks.get(0).getData().get("targetId")).isEqualTo("unit_003");

        assertThat(result.getWinnerPlayerId()).isEqualTo(0);
        assertThat(result.getKeepDamage()).isEqualTo(2);
    }

    @Test
    void determinism_same_seed_same_output() {
        Board board1P = new Board(0);
        Board board1E = new Board(1);
        Board board2P = new Board(0);
        Board board2E = new Board(1);

        board1P.addUnit(new UnitInstance("unit_001", UnitDefinition.squire(), 0, 0));
        board1E.addUnit(new UnitInstance("unit_002", UnitDefinition.squire(), 0, 0));

        board2P.addUnit(new UnitInstance("unit_001", UnitDefinition.squire(), 0, 0));
        board2E.addUnit(new UnitInstance("unit_002", UnitDefinition.squire(), 0, 0));

        long seed = 99999L;
        CombatEngine engine1 = new CombatEngine(seed);
        CombatEngine engine2 = new CombatEngine(seed);

        ResolutionResult result1 = engine1.resolve(board1P, board1E);
        ResolutionResult result2 = engine2.resolve(board2P, board2E);

        assertThat(result1.getEvents().size()).isEqualTo(result2.getEvents().size());
        assertThat(result1.getKeepDamage()).isEqualTo(result2.getKeepDamage());
        assertThat(result1.getEndReason()).isEqualTo(result2.getEndReason());
    }
}
