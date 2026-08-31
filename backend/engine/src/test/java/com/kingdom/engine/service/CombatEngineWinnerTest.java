package com.kingdom.engine.service;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

import com.kingdom.engine.domain.UnitDefinition;
import com.kingdom.engine.domain.UnitInstance;

/** Tests for timeout / tie-break winner logic. */
class CombatEngineWinnerTest {

    @Test
    void determineWinner_equal_hp_more_units_favors_player0() {
        var board = boardWith(
            combatUnit("unit_001", UnitDefinition.squire(), 0, 0, 0, 4),
            combatUnit("unit_003", UnitDefinition.squire(), 1, 0, 0, 4),
            combatUnit("unit_002", UnitDefinition.squire(), 0, 4, 1, 0));

        assertThat(board.getTotalHpForPlayer(0)).isEqualTo(8);
        assertThat(board.getTotalHpForPlayer(1)).isEqualTo(8);
        assertThat(board.getUnitCountForPlayer(0)).isEqualTo(2);
        assertThat(board.getUnitCountForPlayer(1)).isEqualTo(1);

        assertThat(CombatEngine.determineWinner(board)).isZero();
    }

    @Test
    void determineWinner_equal_hp_equal_units_favors_player0() {
        var board = boardWith(
            combatUnit("unit_001", UnitDefinition.healer(), 0, 0, 0, 0),
            combatUnit("unit_002", UnitDefinition.healer(), 0, 4, 1, 0));

        assertThat(CombatEngine.determineWinner(board)).isZero();
    }

    private static com.kingdom.engine.domain.CombatBoard boardWith(UnitInstance... units) {
        var board = new com.kingdom.engine.domain.CombatBoard();
        for (UnitInstance unit : units) {
            board.addUnit(unit);
        }
        return board;
    }

    private static UnitInstance combatUnit(
            String id,
            UnitDefinition definition,
            int x,
            int y,
            int playerId,
            int damageTaken) {
        UnitInstance unit = new UnitInstance(id, definition, x, y);
        unit.setPlayerId(playerId);
        if (damageTaken > 0) {
            unit.takeDamage(damageTaken);
        }
        return unit;
    }
}
