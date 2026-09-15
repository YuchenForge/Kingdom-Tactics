package com.kingdom.api.service;

import com.kingdom.api.dto.ShopSlotDto;
import com.kingdom.api.entity.GameStates;
import com.kingdom.api.entity.Round;
import com.kingdom.api.entity.ShopOffer;
import com.kingdom.api.repository.ShopOfferRepository;
import com.kingdom.engine.domain.UnitTypeResolver;
import com.kingdom.engine.planning.PlanningShop;
import com.kingdom.engine.planning.ShopGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShopServiceTest {

    @Mock
    private ShopOfferRepository shopOfferRepository;

    private ShopService shopService;

    private final UUID gameId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final UUID roundId = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private final UUID playerId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private final UUID player2Id = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @BeforeEach
    void setUp() {
        shopService = new ShopService(shopOfferRepository);
    }

    @Test
    void createShopForPlayer_insertsThreeRowsAtRefreshZero() {
        List<String> expected = ShopGenerator.generateOfferTypes(gameId, 1, playerId, 0);

        shopService.createShopForPlayer(roundId, gameId, 1, playerId);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ShopOffer>> captor = ArgumentCaptor.forClass(List.class);
        verify(shopOfferRepository).saveAll(captor.capture());

        List<ShopOffer> saved = captor.getValue();
        assertThat(saved).hasSize(3);
        for (int slot = 0; slot < 3; slot++) {
            assertThat(saved.get(slot).getRoundId()).isEqualTo(roundId);
            assertThat(saved.get(slot).getPlayerId()).isEqualTo(playerId);
            assertThat(saved.get(slot).getSlot()).isEqualTo(slot);
            assertThat(saved.get(slot).getUnitType()).isEqualTo(expected.get(slot));
        }
    }

    @Test
    void createShopsForRound_createsBothPlayers() {
        Round round = new Round(gameId, 2, GameStates.PREPARATION, java.time.Instant.now());
        ReflectionTestUtils.setField(round, "id", roundId);

        shopService.createShopsForRound(round, gameId, playerId, player2Id);

        verify(shopOfferRepository, times(2)).saveAll(any());
    }

    @Test
    void consumeOffer_nullsUnitType() {
        ShopOffer offer = new ShopOffer(roundId, playerId, 1, "Squire");
        when(shopOfferRepository.findByRoundIdAndPlayerIdAndSlot(roundId, playerId, 1))
                .thenReturn(Optional.of(offer));
        when(shopOfferRepository.save(offer)).thenReturn(offer);

        shopService.consumeOffer(roundId, playerId, 1);

        assertThat(offer.getUnitType()).isNull();
        verify(shopOfferRepository).save(offer);
    }

    @Test
    void consumeOffer_missingRow_throws() {
        when(shopOfferRepository.findByRoundIdAndPlayerIdAndSlot(roundId, playerId, 0))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> shopService.consumeOffer(roundId, playerId, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("shop offer not found");
    }

    @Test
    void persistOffers_writesGivenTypes() {
        List<String> types = List.of("Squire", "Mage", "Ranger");
        for (int slot = 0; slot < 3; slot++) {
            ShopOffer existing = new ShopOffer(roundId, playerId, slot, "Knight");
            when(shopOfferRepository.findByRoundIdAndPlayerIdAndSlot(roundId, playerId, slot))
                    .thenReturn(Optional.of(existing));
            when(shopOfferRepository.save(existing)).thenReturn(existing);
        }

        shopService.persistOffers(roundId, playerId, types);

        ArgumentCaptor<ShopOffer> saved = ArgumentCaptor.forClass(ShopOffer.class);
        verify(shopOfferRepository, times(3)).save(saved.capture());
        assertThat(saved.getAllValues())
                .extracting(ShopOffer::getUnitType)
                .containsExactly("Squire", "Mage", "Ranger");
    }

    @Test
    void persistOffers_rejectsWrongSize() {
        assertThatThrownBy(() -> shopService.persistOffers(roundId, playerId, List.of("Squire")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly 3");
    }

    @Test
    void replaceOffers_updatesExistingRows() {
        List<String> types = ShopGenerator.generateOfferTypes(gameId, 1, playerId, 1);
        for (int slot = 0; slot < 3; slot++) {
            ShopOffer existing = new ShopOffer(roundId, playerId, slot, "Squire");
            when(shopOfferRepository.findByRoundIdAndPlayerIdAndSlot(roundId, playerId, slot))
                    .thenReturn(Optional.of(existing));
            when(shopOfferRepository.save(existing)).thenReturn(existing);
        }

        shopService.replaceOffers(roundId, gameId, 1, playerId, 1);

        for (int slot = 0; slot < 3; slot++) {
            verify(shopOfferRepository).findByRoundIdAndPlayerIdAndSlot(roundId, playerId, slot);
        }
        ArgumentCaptor<ShopOffer> saved = ArgumentCaptor.forClass(ShopOffer.class);
        verify(shopOfferRepository, times(3)).save(saved.capture());
        assertThat(saved.getAllValues())
                .extracting(ShopOffer::getUnitType)
                .containsExactlyElementsOf(types);
    }

    @Test
    void replaceOffers_createsMissingSlots() {
        when(shopOfferRepository.findByRoundIdAndPlayerIdAndSlot(eq(roundId), eq(playerId), anyInt()))
                .thenReturn(Optional.empty());
        when(shopOfferRepository.save(any(ShopOffer.class))).thenAnswer(inv -> inv.getArgument(0));

        shopService.replaceOffers(roundId, gameId, 1, playerId, 0);

        ArgumentCaptor<ShopOffer> saved = ArgumentCaptor.forClass(ShopOffer.class);
        verify(shopOfferRepository, times(3)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(ShopOffer::getSlot).containsExactly(0, 1, 2);
        assertThat(saved.getAllValues()).allMatch(o -> o.getUnitType() != null);
    }

    @Test
    void loadShop_mapsOrderedRowsIncludingSold() {
        when(shopOfferRepository.findByRoundIdAndPlayerIdOrderBySlotAsc(roundId, playerId))
                .thenReturn(List.of(
                        new ShopOffer(roundId, playerId, 0, "Mage"),
                        new ShopOffer(roundId, playerId, 1, null),
                        new ShopOffer(roundId, playerId, 2, "Ranger")));

        PlanningShop shop = shopService.loadShop(roundId, playerId);

        assertThat(shop.get(0)).isEqualTo("Mage");
        assertThat(shop.isEmpty(1)).isTrue();
        assertThat(shop.get(2)).isEqualTo("Ranger");
    }

    @Test
    void toDtos_includesSoldSlotsWithNullTypeAndZeroCost() {
        List<ShopOffer> offers = List.of(
                new ShopOffer(roundId, playerId, 0, "Squire"),
                new ShopOffer(roundId, playerId, 1, null),
                new ShopOffer(roundId, playerId, 2, "Mage"));

        List<ShopSlotDto> dtos = shopService.toDtos(offers);

        assertThat(dtos).hasSize(3);
        assertThat(dtos.get(0)).isEqualTo(
                new ShopSlotDto(0, "Squire", UnitTypeResolver.resolve("Squire").getCost()));
        assertThat(dtos.get(1)).isEqualTo(new ShopSlotDto(1, null, 0));
        assertThat(dtos.get(2)).isEqualTo(
                new ShopSlotDto(2, "Mage", UnitTypeResolver.resolve("Mage").getCost()));
    }
}
