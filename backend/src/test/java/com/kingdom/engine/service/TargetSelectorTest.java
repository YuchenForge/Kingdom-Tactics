package com.kingdom.engine.service;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

import com.kingdom.engine.domain.UnitDefinition;
import com.kingdom.engine.domain.UnitInstance;

class TargetSelectorTest {

    @Test
    void findLowestHpAlly_breaks_ties_by_unit_id() {
        UnitInstance healer = new UnitInstance("unit_001", UnitDefinition.healer(), 0, 0);
        UnitInstance higherId = new UnitInstance("unit_003", UnitDefinition.squire(), 1, 0);
        UnitInstance lowerId = new UnitInstance("unit_002", UnitDefinition.squire(), 2, 0);

        higherId.takeDamage(4);
        lowerId.takeDamage(4);

        assertThat(higherId.getCurrentHp()).isEqualTo(lowerId.getCurrentHp());

        UnitInstance target = TargetSelector.findLowestHpAlly(
            List.of(healer, lowerId, higherId));

        assertThat(target.getId()).isEqualTo("unit_002");
    }
}
