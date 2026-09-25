package com.kingdom.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        assertThat(board.isEmpty()).isFalse();
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
    void getAllUnits_returns_placed_units() {
        Board board = new Board(1);
        UnitInstance a = new UnitInstance("unit_001", UnitDefinition.squire(), 0, 0);
        UnitInstance b = new UnitInstance("unit_002", UnitDefinition.mage(), 1, 1);
        board.addUnit(a);
        board.addUnit(b);

        assertThat(board.getAllUnits()).containsExactlyInAnyOrder(a, b);
    }

    @Test
    void isValidPosition_matches_board_dimensions() {
        assertThat(Board.isValidPosition(0, 0)).isTrue();
        assertThat(Board.isValidPosition(3, 3)).isTrue();
        assertThat(Board.isValidPosition(4, 0)).isFalse();
        assertThat(Board.isValidPosition(0, -1)).isFalse();
    }
}
