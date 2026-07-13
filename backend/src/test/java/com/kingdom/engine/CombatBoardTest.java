package com.kingdom.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.kingdom.engine.domain.Board;
import com.kingdom.engine.domain.CombatBoard;
import com.kingdom.engine.domain.UnitDefinition;
import com.kingdom.engine.domain.UnitInstance;

class CombatBoardTest {

    @Test
    void merge_maps_player0_with_180_degree_rotation() {
        Board player0 = new Board(0);
        player0.addUnit(new UnitInstance("unit_001", UnitDefinition.squire(), 2, 0));

        CombatBoard combatBoard = CombatBoard.merge(player0, new Board(1));
        UnitInstance unit = combatBoard.getUnit("unit_001");

        assertThat(unit.getX()).isEqualTo(1);
        assertThat(unit.getY()).isEqualTo(3);
        assertThat(unit.getPlayerId()).isZero();
    }

    @Test
    void merge_maps_player1_with_row_offset() {
        Board player1 = new Board(1);
        player1.addUnit(new UnitInstance("unit_002", UnitDefinition.squire(), 1, 2));

        CombatBoard combatBoard = CombatBoard.merge(new Board(0), player1);
        UnitInstance unit = combatBoard.getUnit("unit_002");

        assertThat(unit.getX()).isEqualTo(1);
        assertThat(unit.getY()).isEqualTo(6);
        assertThat(unit.getPlayerId()).isEqualTo(1);
    }

    @Test
    void merge_resets_damaged_units_to_full_hp() {
        Board player0 = new Board(0);
        Board player1 = new Board(1);
        UnitInstance damaged = new UnitInstance("unit_001", UnitDefinition.squire(), 0, 0);
        damaged.takeDamage(5);
        player0.addUnit(damaged);
        player1.addUnit(new UnitInstance("unit_002", UnitDefinition.knight(), 3, 3));

        CombatBoard combatBoard = CombatBoard.merge(player0, player1);

        assertThat(combatBoard.getUnit("unit_001").getCurrentHp()).isEqualTo(8);
        assertThat(combatBoard.getUnit("unit_002").getCurrentHp()).isEqualTo(18);
    }

    @Test
    void addUnit_requires_playerId() {
        CombatBoard board = new CombatBoard();
        UnitInstance unit = new UnitInstance("unit_001", UnitDefinition.squire(), 0, 0);

        assertThatThrownBy(() -> board.addUnit(unit))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("playerId");
    }

    @Test
    void addUnit_rejects_invalid_position_duplicate_id_and_occupied_cell() {
        CombatBoard board = new CombatBoard();
        board.addUnit(combatUnit("unit_001", UnitDefinition.squire(), 0, 0, 0));

        assertThatThrownBy(() -> board.addUnit(combatUnit("unit_002", UnitDefinition.squire(), 8, 0, 0)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Invalid position");

        assertThatThrownBy(() -> board.addUnit(combatUnit("unit_001", UnitDefinition.knight(), 1, 0, 0)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("already on board");

        assertThatThrownBy(() -> board.addUnit(combatUnit("unit_003", UnitDefinition.squire(), 0, 0, 1)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Position occupied");
    }

    @Test
    void getAliveUnitsForPlayer_and_player_totals() {
        CombatBoard board = new CombatBoard();
        board.addUnit(combatUnit("unit_001", UnitDefinition.squire(), 0, 0, 0));
        board.addUnit(combatUnit("unit_002", UnitDefinition.knight(), 1, 0, 0));
        board.addUnit(combatUnit("unit_003", UnitDefinition.squire(), 0, 4, 1));

        assertThat(board.getAliveUnitsForPlayer(0))
            .extracting(UnitInstance::getId)
            .containsExactly("unit_001", "unit_002");
        assertThat(board.getUnitCountForPlayer(0)).isEqualTo(2);
        assertThat(board.getTotalHpForPlayer(0)).isEqualTo(8 + 18);
        assertThat(board.getUnitCountForPlayer(1)).isEqualTo(1);
        assertThat(board.isEmptyForPlayer(1)).isFalse();
    }

    @Test
    void removeDead_and_isOccupied_ignore_dead_units() {
        CombatBoard board = new CombatBoard();
        UnitInstance unit = combatUnit("unit_001", UnitDefinition.squire(), 2, 2, 0);
        board.addUnit(unit);
        unit.takeDamage(8);

        assertThat(board.isOccupied(2, 2)).isFalse();
        assertThat(board.getUnitAt(2, 2)).isNull();

        board.removeDead();

        assertThat(board.getUnit("unit_001")).isNull();
        assertThat(board.isEmptyForPlayer(0)).isTrue();
    }

    @Test
    void copy_constructor_creates_independent_unit_state() {
        CombatBoard original = new CombatBoard();
        UnitInstance unit = combatUnit("unit_001", UnitDefinition.squire(), 1, 1, 0);
        original.addUnit(unit);
        unit.takeDamage(2);

        CombatBoard copy = new CombatBoard(original);
        copy.getUnit("unit_001").takeDamage(3);

        assertThat(original.getUnit("unit_001").getCurrentHp()).isEqualTo(6);
        assertThat(copy.getUnit("unit_001").getCurrentHp()).isEqualTo(3);
        assertThat(copy.getPlayerIdForUnit("unit_001")).isEqualTo(0);
    }

    @Test
    void merge_rejects_mismatched_board_playerId() {
        Board wrongPlayerId = new Board(1);

        assertThatThrownBy(() -> CombatBoard.merge(wrongPlayerId, new Board(1)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("does not match");
    }

    @Test
    void isValidPosition_matches_combat_grid() {
        assertThat(CombatBoard.isValidPosition(0, 0)).isTrue();
        assertThat(CombatBoard.isValidPosition(3, 7)).isTrue();
        assertThat(CombatBoard.isValidPosition(0, 8)).isFalse();
    }

    private static UnitInstance combatUnit(
            String id,
            UnitDefinition definition,
            int x,
            int y,
            int playerId) {
        UnitInstance unit = new UnitInstance(id, definition, x, y);
        unit.setPlayerId(playerId);
        return unit;
    }
}
