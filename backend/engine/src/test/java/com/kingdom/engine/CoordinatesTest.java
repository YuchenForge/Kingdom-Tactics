package com.kingdom.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.kingdom.engine.domain.Coordinates;

class CoordinatesTest {

    @Test
    void player0_local_to_combat_examples() {
        assertThat(Coordinates.toCombatX(2, 0)).isEqualTo(1);
        assertThat(Coordinates.toCombatY(0, 0)).isEqualTo(3);
        assertThat(Coordinates.toCombatX(0, 0)).isEqualTo(3);
        assertThat(Coordinates.toCombatY(3, 0)).isZero();
        assertThat(Coordinates.toCombatX(3, 0)).isZero();
        assertThat(Coordinates.toCombatY(1, 0)).isEqualTo(2);
    }

    @Test
    void player1_local_to_combat_examples() {
        assertThat(Coordinates.toCombatX(1, 1)).isEqualTo(1);
        assertThat(Coordinates.toCombatY(2, 1)).isEqualTo(6);
        assertThat(Coordinates.toCombatX(0, 1)).isZero();
        assertThat(Coordinates.toCombatY(0, 1)).isEqualTo(4);
        assertThat(Coordinates.toCombatX(3, 1)).isEqualTo(3);
        assertThat(Coordinates.toCombatY(3, 1)).isEqualTo(7);
    }

    @Test
    void isValidPlacement_and_combat_boundaries() {
        assertThat(Coordinates.isValidPlacement(0, 0)).isTrue();
        assertThat(Coordinates.isValidPlacement(3, 3)).isTrue();
        assertThat(Coordinates.isValidPlacement(-1, 0)).isFalse();
        assertThat(Coordinates.isValidPlacement(4, 0)).isFalse();

        assertThat(Coordinates.isValidCombat(0, 0)).isTrue();
        assertThat(Coordinates.isValidCombat(3, 7)).isTrue();
        assertThat(Coordinates.isValidCombat(3, 8)).isFalse();
        assertThat(Coordinates.isValidCombat(4, 0)).isFalse();
    }

    @Test
    void invalid_playerId_throws() {
        assertThatThrownBy(() -> Coordinates.toCombatX(0, 2))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("playerId");
    }

    @Test
    void invalid_local_coordinates_throw() {
        assertThatThrownBy(() -> Coordinates.toCombatX(-1, 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Coordinates.toCombatY(4, 1))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
