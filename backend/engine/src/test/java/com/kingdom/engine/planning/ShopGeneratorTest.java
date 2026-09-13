package com.kingdom.engine.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kingdom.engine.domain.UnitTypeResolver;

class ShopGeneratorTest {

    private static final UUID GAME = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PLAYER_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID PLAYER_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @Test
    void sameInputs_yieldIdenticalOffers() {
        List<String> first = ShopGenerator.generateOfferTypes(GAME, 1, PLAYER_A, 0);
        List<String> second = ShopGenerator.generateOfferTypes(GAME, 1, PLAYER_A, 0);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void offers_areAlwaysLengthThreeAndKnownTypes() {
        List<String> offers = ShopGenerator.generateOfferTypes(GAME, 1, PLAYER_A, 0);

        assertThat(offers).hasSize(PlanningShop.SLOT_COUNT);
        assertThat(offers).allMatch(UnitTypeResolver::isKnownType);
    }

    @Test
    void goldenOffers_forFixedSeedInputs() {
        // Pins the seed → Random contract; change only if pool or seed formula changes.
        assertThat(ShopGenerator.generateOfferTypes(GAME, 1, PLAYER_A, 0))
                .containsExactly("Mage", "Knight", "Ranger");
        assertThat(ShopGenerator.generateOfferTypes(GAME, 1, PLAYER_A, 1))
                .containsExactly("Shieldbearer", "Healer", "Knight");
    }

    @Test
    void seed_differsWhenInputsDiffer() {
        long base = ShopGenerator.seed(GAME, 1, PLAYER_A, 0);

        assertThat(ShopGenerator.seed(GAME, 1, PLAYER_A, 1)).isNotEqualTo(base);
        assertThat(ShopGenerator.seed(GAME, 2, PLAYER_A, 0)).isNotEqualTo(base);
        assertThat(ShopGenerator.seed(GAME, 1, PLAYER_B, 0)).isNotEqualTo(base);
    }

    @Test
    void negativeRefreshIndex_rejected() {
        assertThatThrownBy(() -> ShopGenerator.generateOfferTypes(GAME, 1, PLAYER_A, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("refreshIndex");
    }
}
