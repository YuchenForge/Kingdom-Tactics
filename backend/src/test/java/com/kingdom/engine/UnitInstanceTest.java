package com.kingdom.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.kingdom.engine.domain.UnitDefinition;
import com.kingdom.engine.domain.UnitInstance;

class UnitInstanceTest {

    @Test
    void new_unit_starts_at_max_hp_with_initial_cooldown() {
        UnitInstance squire = new UnitInstance("unit_001", UnitDefinition.squire(), 1, 2);

        assertThat(squire.getCurrentHp()).isEqualTo(8);
        assertThat(squire.getCooldownTicks()).isEqualTo(UnitInstance.INITIAL_COOLDOWN);
        assertThat(squire.getActionCounter()).isZero();
        assertThat(squire.isAlive()).isTrue();
        assertThat(squire.getX()).isEqualTo(1);
        assertThat(squire.getY()).isEqualTo(2);
    }

    @Test
    void setPlayerId_accepts_0_and_1_only() {
        UnitInstance unit = new UnitInstance("unit_001", UnitDefinition.squire(), 0, 0);

        unit.setPlayerId(0);
        unit.setPlayerId(1);

        assertThatThrownBy(() -> unit.setPlayerId(2))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("playerId");
    }

    @Test
    void cooldown_cycle_controls_action_readiness() {
        UnitInstance unit = new UnitInstance("unit_001", UnitDefinition.squire(), 0, 0);

        assertThat(unit.canAct()).isFalse();

        for (int i = 0; i < UnitInstance.INITIAL_COOLDOWN; i++) {
            unit.decrementCooldown();
        }

        assertThat(unit.canAct()).isTrue();

        unit.resetCooldown();

        assertThat(unit.canAct()).isFalse();
        assertThat(unit.getCooldownTicks()).isEqualTo(UnitInstance.INITIAL_COOLDOWN);
    }

    @Test
    void dead_unit_cannot_act() {
        UnitInstance unit = new UnitInstance("unit_001", UnitDefinition.squire(), 0, 0);
        unit.takeDamage(8);

        assertThat(unit.canAct()).isFalse();
    }

    @Test
    void action_counter_triggers_special_on_every_third_attack() {
        UnitInstance mage = new UnitInstance("unit_001", UnitDefinition.mage(), 0, 0);

        assertThat(mage.willTriggerSpecialOnNextAttack()).isFalse();

        mage.incrementActionCounter();
        assertThat(mage.willTriggerSpecialOnNextAttack()).isFalse();
        assertThat(mage.isTriggerSpecialAction()).isFalse();

        mage.incrementActionCounter();
        assertThat(mage.willTriggerSpecialOnNextAttack()).isTrue();
        assertThat(mage.isTriggerSpecialAction()).isFalse();

        mage.incrementActionCounter();
        assertThat(mage.isTriggerSpecialAction()).isTrue();
        assertThat(mage.willTriggerSpecialOnNextAttack()).isFalse();

        mage.incrementActionCounter();
        mage.incrementActionCounter();
        assertThat(mage.willTriggerSpecialOnNextAttack()).isTrue();
    }

    @Test
    void takeDamage_applies_minimum_one_and_kills_at_zero_hp() {
        UnitInstance squire = new UnitInstance("unit_001", UnitDefinition.squire(), 0, 0);

        squire.takeDamage(0);
        assertThat(squire.getCurrentHp()).isEqualTo(7);

        squire.takeDamage(7);
        assertThat(squire.getCurrentHp()).isZero();
        assertThat(squire.isAlive()).isFalse();
    }

    @Test
    void heal_is_capped_at_max_hp() {
        UnitInstance squire = new UnitInstance("unit_001", UnitDefinition.squire(), 0, 0);
        squire.takeDamage(5);

        squire.heal(3);
        assertThat(squire.getCurrentHp()).isEqualTo(6);

        squire.heal(10);
        assertThat(squire.getCurrentHp()).isEqualTo(8);
    }

    @Test
    void distance_metrics_use_current_positions() {
        UnitInstance a = new UnitInstance("unit_001", UnitDefinition.squire(), 0, 0);
        UnitInstance b = new UnitInstance("unit_002", UnitDefinition.squire(), 2, 3);

        assertThat(a.chebyshevDistance(b)).isEqualTo(3);
        assertThat(a.manhattanDistance(b)).isEqualTo(5);
    }

    @Test
    void getArmor_applies_only_to_shieldbearer() {
        UnitInstance shieldbearer = new UnitInstance("unit_001", UnitDefinition.shieldbearer(), 0, 0);
        UnitInstance squire = new UnitInstance("unit_002", UnitDefinition.squire(), 0, 0);

        assertThat(shieldbearer.getArmor()).isEqualTo(1);
        assertThat(squire.getArmor()).isZero();
    }

    @Test
    void setPosition_updates_coordinates() {
        UnitInstance unit = new UnitInstance("unit_001", UnitDefinition.squire(), 0, 0);

        unit.setPosition(3, 2);

        assertThat(unit.getX()).isEqualTo(3);
        assertThat(unit.getY()).isEqualTo(2);
    }

    @Test
    void equals_and_hashCode_use_unit_id() {
        UnitInstance first = new UnitInstance("unit_001", UnitDefinition.squire(), 0, 0);
        UnitInstance sameId = new UnitInstance("unit_001", UnitDefinition.knight(), 1, 1);
        UnitInstance differentId = new UnitInstance("unit_002", UnitDefinition.squire(), 0, 0);

        assertThat(first).isEqualTo(sameId);
        assertThat(first).isNotEqualTo(differentId);
        assertThat(first.hashCode()).isEqualTo(sameId.hashCode());
    }
}
