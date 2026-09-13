package com.kingdom.api.shop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kingdom.api.dto.GameStateResponse;
import com.kingdom.api.dto.ShopSlotDto;
import com.kingdom.api.entity.Round;
import com.kingdom.api.entity.ShopOffer;
import com.kingdom.api.repository.RoundRepository;
import com.kingdom.api.repository.ShopOfferRepository;
import com.kingdom.api.service.ShopService;
import com.kingdom.api.support.AbstractPostgresIT;
import com.kingdom.api.support.TestAuthSupport;
import com.kingdom.engine.planning.PlanningShop;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ShopServiceIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ShopService shopService;

    @Autowired
    private ShopOfferRepository shopOfferRepository;

    @Autowired
    private RoundRepository roundRepository;

    @Test
    void joinCreatesSixNonNullOffers() throws Exception {
        JoinedGame game = joinTwoPlayers();

        List<ShopOffer> alice = shopOfferRepository
                .findByRoundIdAndPlayerIdOrderBySlotAsc(game.roundId(), game.aliceId());
        List<ShopOffer> bob = shopOfferRepository
                .findByRoundIdAndPlayerIdOrderBySlotAsc(game.roundId(), game.bobId());

        assertThat(alice).hasSize(PlanningShop.SLOT_COUNT);
        assertThat(bob).hasSize(PlanningShop.SLOT_COUNT);
        assertThat(alice).allSatisfy(o -> assertThat(o.getUnitType()).isNotBlank());
        assertThat(bob).allSatisfy(o -> assertThat(o.getUnitType()).isNotBlank());
    }

    @Test
    void consumeOffer_nullsOnlyThatSlot() throws Exception {
        JoinedGame game = joinTwoPlayers();

        List<ShopOffer> before = shopOfferRepository
                .findByRoundIdAndPlayerIdOrderBySlotAsc(game.roundId(), game.aliceId());
        String slot0 = before.get(0).getUnitType();
        String slot2 = before.get(2).getUnitType();

        shopService.consumeOffer(game.roundId(), game.aliceId(), 1);

        List<ShopOffer> after = shopOfferRepository
                .findByRoundIdAndPlayerIdOrderBySlotAsc(game.roundId(), game.aliceId());
        assertThat(after.get(0).getUnitType()).isEqualTo(slot0);
        assertThat(after.get(1).getUnitType()).isNull();
        assertThat(after.get(2).getUnitType()).isEqualTo(slot2);
    }

    @Test
    void replaceOffers_updatesInPlaceWithoutDuplicateRows() throws Exception {
        JoinedGame game = joinTwoPlayers();

        List<String> before = shopOfferRepository
                .findByRoundIdAndPlayerIdOrderBySlotAsc(game.roundId(), game.aliceId())
                .stream()
                .map(ShopOffer::getUnitType)
                .toList();

        shopService.replaceOffers(game.roundId(), game.gameId(), 1, game.aliceId(), 1);

        List<ShopOffer> afterRows = shopOfferRepository
                .findByRoundIdAndPlayerIdOrderBySlotAsc(game.roundId(), game.aliceId());
        List<String> after = afterRows.stream().map(ShopOffer::getUnitType).toList();

        assertThat(afterRows).hasSize(PlanningShop.SLOT_COUNT);
        assertThat(after).doesNotContainNull();
        assertThat(after).isNotEqualTo(before);
    }

    @Test
    void getState_showsOnlyViewerOffers() throws Exception {
        JoinedGame game = joinTwoPlayers();

        List<String> aliceTypes = shopOfferRepository
                .findByRoundIdAndPlayerIdOrderBySlotAsc(game.roundId(), game.aliceId())
                .stream()
                .map(ShopOffer::getUnitType)
                .toList();
        List<String> bobTypes = shopOfferRepository
                .findByRoundIdAndPlayerIdOrderBySlotAsc(game.roundId(), game.bobId())
                .stream()
                .map(ShopOffer::getUnitType)
                .toList();

        MvcResult state = mockMvc.perform(get("/api/games/" + game.gameId() + "/state")
                        .header("Authorization", "Bearer " + game.aliceToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shop", hasSize(3)))
                .andReturn();

        GameStateResponse response = objectMapper.readValue(
                state.getResponse().getContentAsString(), GameStateResponse.class);
        List<String> shopTypes = response.shop().stream().map(ShopSlotDto::unitType).toList();

        assertThat(shopTypes).isEqualTo(aliceTypes);
        if (!aliceTypes.equals(bobTypes)) {
            assertThat(shopTypes).isNotEqualTo(bobTypes);
        }
    }

    private JoinedGame joinTwoPlayers() throws Exception {
        MvcResult aliceReg = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"shop_alice","email":"shop_alice@test.com","password":"password123"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        String aliceBody = aliceReg.getResponse().getContentAsString();
        String aliceToken = TestAuthSupport.extractJsonField(aliceBody, "token");
        UUID aliceId = UUID.fromString(TestAuthSupport.extractJsonField(aliceBody, "userId"));

        MvcResult bobReg = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"shop_bob","email":"shop_bob@test.com","password":"password123"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        String bobBody = bobReg.getResponse().getContentAsString();
        String bobToken = TestAuthSupport.extractJsonField(bobBody, "token");
        UUID bobId = UUID.fromString(TestAuthSupport.extractJsonField(bobBody, "userId"));

        String gameId = TestAuthSupport.createGame(mockMvc, aliceToken);
        mockMvc.perform(post("/api/games/" + gameId + "/join")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk());

        UUID gameUuid = UUID.fromString(gameId);
        Round round = roundRepository.findByGameIdAndRoundNumber(gameUuid, 1).orElseThrow();
        return new JoinedGame(gameUuid, round.getId(), aliceId, bobId, aliceToken, bobToken);
    }

    private record JoinedGame(
            UUID gameId,
            UUID roundId,
            UUID aliceId,
            UUID bobId,
            String aliceToken,
            String bobToken) {
    }
}
