package com.kingdom.engine.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class CombatSeedGeneratorTest {

    private static final UUID GAME = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_GAME = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void sameInputs_yieldIdenticalSeed() {
        long first = CombatSeedGenerator.seed(GAME, 1);
        long second = CombatSeedGenerator.seed(GAME, 1);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void differentRound_yieldsDifferentSeed() {
        assertThat(CombatSeedGenerator.seed(GAME, 1))
                .isNotEqualTo(CombatSeedGenerator.seed(GAME, 2));
    }

    @Test
    void differentGame_yieldsDifferentSeed() {
        assertThat(CombatSeedGenerator.seed(GAME, 1))
                .isNotEqualTo(CombatSeedGenerator.seed(OTHER_GAME, 1));
    }
}
