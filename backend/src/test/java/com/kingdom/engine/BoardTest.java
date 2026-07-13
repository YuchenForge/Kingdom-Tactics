package com.kingdom.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.kingdom.engine.domain.Board;
import com.kingdom.engine.domain.UnitDefinition;
import com.kingdom.engine.domain.UnitInstance;

class BoardTest {

    @Test
    void addUnit_places_unit_on_valid_position() {
        Board board = new Board(0);
        UnitInstance squire = new UnitInstance("unit_001", UnitDefinition.squire(), 2, 1);

        board.addUnit(squire);

        assertThat(board.getUnit("unit_001")).isSameAs(squire);
        assertThat(board.getUnitCount()).isEqualTo(1);
        assertThat(board.getPlayerId()).isZero();
    }

    @Test
    void addUnit_rejects_invalid_position() {
        Board board = new Board(0);
        UnitInstance outOfBounds = new UnitInstance("unit_001", UnitDefinition.squire(), 4, 0);

        assertThatThrownBy(() -> board.addUnit(outOfBounds))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Invalid position");
    }

    @Test
    void addUnit_rejects_duplicate_unit_id() {
        Board board = new Board(0);
        board.addUnit(new UnitInstance("unit_001", UnitDefinition.squire(), 0, 0));

        assertThatThrownBy(() -> board.addUnit(new UnitInstance("unit_001", UnitDefinition.knight(), 1, 1)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("already on board");
    }

    @Test
    void removeUnit_and_getUnitAt() {
        Board board = new Board(1);
        UnitInstance unit = new UnitInstance("unit_001", UnitDefinition.squire(), 1, 2);
        board.addUnit(unit);

        assertThat(board.getUnitAt(1, 2)).isSameAs(unit);
        assertThat(board.isOccupied(1, 2)).isTrue();

        board.removeUnit("unit_001");

        assertThat(board.getUnit("unit_001")).isNull();
        assertThat(board.getUnitAt(1, 2)).isNull();
        assertThat(board.isEmpty()).isTrue();
    }

    @Test
    void getAliveUnits_sorted_by_id() {
        Board board = new Board(0);
        board.addUnit(new UnitInstance("unit_003", UnitDefinition.squire(), 0, 0));
        board.addUnit(new UnitInstance("unit_001", UnitDefinition.squire(), 1, 0));
        board.addUnit(new UnitInstance("unit_002", UnitDefinition.squire(), 2, 0));

        List<UnitInstance> alive = board.getAliveUnits();

        assertThat(alive).extracting(UnitInstance::getId)
            .containsExactly("unit_001", "unit_002", "unit_003");
    }

    @Test
    void getDeadUnits_and_removeDead() {
        Board board = new Board(0);
        UnitInstance dead = new UnitInstance("unit_001", UnitDefinition.squire(), 0, 0);
        board.addUnit(dead);
        board.addUnit(new UnitInstance("unit_002", UnitDefinition.squire(), 1, 0));
        dead.takeDamage(8);

        assertThat(board.getDeadUnits()).hasSize(1);
        assertThat(board.getUnitCount()).isEqualTo(1);

        board.removeDead();

        assertThat(board.getAllUnits()).hasSize(1);
        assertThat(board.getUnit("unit_001")).isNull();
    }

    @Test
    void dead_unit_does_not_occupy_position() {
        Board board = new Board(0);
        UnitInstance unit = new UnitInstance("unit_001", UnitDefinition.squire(), 2, 2);
        board.addUnit(unit);
        unit.takeDamage(8);

        assertThat(board.isOccupied(2, 2)).isFalse();
        assertThat(board.getUnitAt(2, 2)).isNull();
    }

    @Test
    void getTotalHp_sums_alive_units_only() {
        Board board = new Board(0);
        UnitInstance wounded = new UnitInstance("unit_001", UnitDefinition.squire(), 0, 0);
        board.addUnit(wounded);
        board.addUnit(new UnitInstance("unit_002", UnitDefinition.knight(), 1, 0));
        wounded.takeDamage(3);

        assertThat(board.getTotalHp()).isEqualTo(5 + 18);
    }

    @Test
    void getEnemiesInRange_uses_chebyshev_distance() {
        Board playerBoard = new Board(0);
        Board enemyBoard = new Board(1);
        UnitInstance knight = new UnitInstance("unit_001", UnitDefinition.knight(), 0, 0);
        UnitInstance inRange = new UnitInstance("unit_002", UnitDefinition.squire(), 0, 1);
        UnitInstance outOfRange = new UnitInstance("unit_003", UnitDefinition.squire(), 0, 2);

        playerBoard.addUnit(knight);
        enemyBoard.addUnit(inRange);
        enemyBoard.addUnit(outOfRange);

        assertThat(playerBoard.getEnemiesInRange(knight, enemyBoard))
            .extracting(UnitInstance::getId)
            .containsExactly("unit_002");
    }

    @Test
    void copy_constructor_shares_unit_references() {
        Board original = new Board(0);
        UnitInstance unit = new UnitInstance("unit_001", UnitDefinition.squire(), 1, 1);
        original.addUnit(unit);

        Board copy = new Board(original);

        assertThat(copy.getUnit("unit_001")).isSameAs(unit);
        assertThat(copy.getPlayerId()).isEqualTo(original.getPlayerId());
    }

    @Test
    void copyForNextRound_restores_full_hp_at_locked_positions() {
        Board board = new Board(0);
        UnitInstance unit = new UnitInstance("unit_001", UnitDefinition.squire(), 2, 3);
        board.addUnit(unit);
        unit.takeDamage(5);

        Board nextRound = board.copyForNextRound();
        UnitInstance restored = nextRound.getUnit("unit_001");

        assertThat(restored.getCurrentHp()).isEqualTo(8);
        assertThat(restored.getX()).isEqualTo(2);
        assertThat(restored.getY()).isEqualTo(3);
        assertThat(restored.isAlive()).isTrue();
    }

    @Test
    void boardsForNextRound_copies_both_players() {
        Board player0 = new Board(0);
        Board player1 = new Board(1);
        player0.addUnit(new UnitInstance("unit_001", UnitDefinition.squire(), 0, 0));
        player1.addUnit(new UnitInstance("unit_002", UnitDefinition.knight(), 3, 3));

        Board[] next = Board.boardsForNextRound(player0, player1);

        assertThat(next).hasSize(2);
        assertThat(next[0].getPlayerId()).isZero();
        assertThat(next[1].getPlayerId()).isEqualTo(1);
        assertThat(next[0].getUnit("unit_001").getCurrentHp()).isEqualTo(8);
        assertThat(next[1].getUnit("unit_002").getCurrentHp()).isEqualTo(18);
    }

    @Test
    void isValidPosition_matches_board_dimensions() {
        assertThat(Board.isValidPosition(0, 0)).isTrue();
        assertThat(Board.isValidPosition(3, 3)).isTrue();
        assertThat(Board.isValidPosition(4, 0)).isFalse();
        assertThat(Board.isValidPosition(0, -1)).isFalse();
    }
}
