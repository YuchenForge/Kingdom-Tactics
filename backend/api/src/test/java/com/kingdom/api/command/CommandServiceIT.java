package com.kingdom.api.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kingdom.api.entity.Command;
import com.kingdom.api.entity.Game;
import com.kingdom.api.entity.GameStates;
import com.kingdom.api.entity.Round;
import com.kingdom.api.entity.RoundPlan;
import com.kingdom.api.entity.ShopOffer;
import com.kingdom.api.repository.CommandRepository;
import com.kingdom.api.repository.GameRepository;
import com.kingdom.api.repository.RoundPlanRepository;
import com.kingdom.api.repository.RoundRepository;
import com.kingdom.api.repository.ShopOfferRepository;
import com.kingdom.api.service.ShopService;
import com.kingdom.api.support.AbstractPostgresIT;
import com.kingdom.api.support.TestAuthSupport;
import com.kingdom.engine.planning.ShopGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Command HTTP flows. Uses NOT_SUPPORTED so idempotency recovery REQUIRES_NEW
 * can see committed setup (production does not paper over uncommitted test TX).
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CommandServiceIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CommandRepository commandRepository;

    @Autowired
    private GameRepository gameRepository;

    @Autowired
    private RoundRepository roundRepository;

    @Autowired
    private RoundPlanRepository roundPlanRepository;

    @Autowired
    private ShopService shopService;

    @Autowired
    private ShopOfferRepository shopOfferRepository;

    @Test
    void buy_thenIdempotentRetry_sameGold_oneCommandRow() throws Exception {
        TestAuthSupport.JoinedGame game = TestAuthSupport.joinTwoPlayers(mockMvc, "cmd_idem");
        UUID key = UUID.randomUUID();
        UUID planId = alicePlanId(game);
        int expectedGold = 10 - shopSlotCost(game.gameId(), game.aliceToken(), 0);

        mockMvc.perform(post("/api/games/" + game.gameId() + "/rounds/1/buy")
                        .header("Authorization", "Bearer " + game.aliceToken())
                        .header("Idempotency-Key", key.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shopSlot\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.gold", is(expectedGold)))
                .andExpect(jsonPath("$.lane[0].unitId", notNullValue()))
                .andExpect(jsonPath("$.lane[0].unitType", notNullValue()))
                .andExpect(jsonPath("$.shop[0].unitType", nullValue()));

        mockMvc.perform(post("/api/games/" + game.gameId() + "/rounds/1/buy")
                        .header("Authorization", "Bearer " + game.aliceToken())
                        .header("Idempotency-Key", key.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shopSlot\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gold", is(expectedGold)))
                .andExpect(jsonPath("$.lane[0].unitId", notNullValue()));

        List<Command> commands = commandRepository.findAll().stream()
                .filter(c -> planId.equals(c.getRoundPlanId()))
                .toList();
        assertThat(commands).hasSize(1);
        assertThat(commands.get(0).getIdempotencyKey()).isEqualTo(key);
    }

    @Test
    void buy_missingIdempotencyKey_returns400() throws Exception {
        TestAuthSupport.JoinedGame game = TestAuthSupport.joinTwoPlayers(mockMvc, "cmd_nokey");

        mockMvc.perform(post("/api/games/" + game.gameId() + "/rounds/1/buy")
                        .header("Authorization", "Bearer " + game.aliceToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shopSlot\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("VALIDATION_ERROR")));
    }

    @Test
    void buy_missingShopSlot_returns400() throws Exception {
        TestAuthSupport.JoinedGame game = TestAuthSupport.joinTwoPlayers(mockMvc, "cmd_noslot");

        mockMvc.perform(post("/api/games/" + game.gameId() + "/rounds/1/buy")
                        .header("Authorization", "Bearer " + game.aliceToken())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("VALIDATION_ERROR")));
    }

    @Test
    void buy_asNonParticipant_returns403() throws Exception {
        TestAuthSupport.JoinedGame game = TestAuthSupport.joinTwoPlayers(mockMvc, "cmd_outsider");
        String charlieToken = TestAuthSupport.register(mockMvc, "cmd_charlie", "cmd_charlie@test.com");

        mockMvc.perform(post("/api/games/" + game.gameId() + "/rounds/1/buy")
                        .header("Authorization", "Bearer " + charlieToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shopSlot\":0}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error", is("NOT_GAME_PARTICIPANT")));
    }

    @Test
    void buy_whileWaiting_returns404RoundNotFound() throws Exception {
        MvcResult aliceReg = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"cmd_wait_a","email":"cmd_wait_a@test.com","password":"password123"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        String aliceToken = TestAuthSupport.extractJsonField(
                aliceReg.getResponse().getContentAsString(), "token");
        String gameId = TestAuthSupport.createGame(mockMvc, aliceToken);

        // No round exists until the second player joins — cannot reach PREPARATION guards yet.
        mockMvc.perform(post("/api/games/" + gameId + "/rounds/1/buy")
                        .header("Authorization", "Bearer " + aliceToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shopSlot\":0}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", is("ROUND_NOT_FOUND")));
    }

    @Test
    void afterOwnLock_buyRejected_opponentStillActs() throws Exception {
        TestAuthSupport.JoinedGame game = TestAuthSupport.joinTwoPlayers(mockMvc, "cmd_ownlock");

        mockMvc.perform(post("/api/games/" + game.gameId() + "/rounds/1/lock")
                        .header("Authorization", "Bearer " + game.aliceToken())
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isLocked", is(true)))
                .andExpect(jsonPath("$.opponentIsLocked", is(false)));

        mockMvc.perform(post("/api/games/" + game.gameId() + "/rounds/1/buy")
                        .header("Authorization", "Bearer " + game.aliceToken())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shopSlot\":0}"))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.error", is("LOCKED")));

        mockMvc.perform(post("/api/games/" + game.gameId() + "/rounds/1/relocate")
                        .header("Authorization", "Bearer " + game.aliceToken())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"unitId":"nope","to":{"type":"BOARD","x":0,"y":0}}
                                """))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.error", is("LOCKED")));

        mockMvc.perform(post("/api/games/" + game.gameId() + "/rounds/1/buy")
                        .header("Authorization", "Bearer " + game.bobToken())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shopSlot\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success", is(true)));

        Game stillPrep = gameRepository.findById(UUID.fromString(game.gameId())).orElseThrow();
        assertThat(stillPrep.getState()).isEqualTo(GameStates.PREPARATION);
    }

    @Test
    void planningFlow_relocateLockBoth_thenBuyRejectedAndShopHidden() throws Exception {
        TestAuthSupport.JoinedGame game = TestAuthSupport.joinTwoPlayers(mockMvc, "cmd_flow");
        int expectedGold = 10 - shopSlotCost(game.gameId(), game.aliceToken(), 0);

        MvcResult buyResult = mockMvc.perform(post("/api/games/" + game.gameId() + "/rounds/1/buy")
                        .header("Authorization", "Bearer " + game.aliceToken())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shopSlot\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gold", is(expectedGold)))
                .andReturn();

        String unitId = objectMapper.readTree(buyResult.getResponse().getContentAsString())
                .path("lane").get(0).path("unitId").asText();

        mockMvc.perform(post("/api/games/" + game.gameId() + "/rounds/1/relocate")
                        .header("Authorization", "Bearer " + game.aliceToken())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"unitId":"%s","to":{"type":"BOARD","x":0,"y":0}}
                                """.formatted(unitId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.board[0][0]", is(unitId)))
                .andExpect(jsonPath("$.lane[0].unitId", nullValue()));

        mockMvc.perform(post("/api/games/" + game.gameId() + "/rounds/1/lock")
                        .header("Authorization", "Bearer " + game.aliceToken())
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isLocked", is(true)))
                .andExpect(jsonPath("$.opponentIsLocked", is(false)));

        MvcResult bobBuy = mockMvc.perform(post("/api/games/" + game.gameId() + "/rounds/1/buy")
                        .header("Authorization", "Bearer " + game.bobToken())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shopSlot\":0}"))
                .andExpect(status().isOk())
                .andReturn();
        String bobUnitId = objectMapper.readTree(bobBuy.getResponse().getContentAsString())
                .path("lane").get(0).path("unitId").asText();

        mockMvc.perform(post("/api/games/" + game.gameId() + "/rounds/1/relocate")
                        .header("Authorization", "Bearer " + game.bobToken())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"unitId":"%s","to":{"type":"BOARD","x":1,"y":1}}
                                """.formatted(bobUnitId)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/games/" + game.gameId() + "/rounds/1/lock")
                        .header("Authorization", "Bearer " + game.bobToken())
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.opponentIsLocked", is(true)))
                .andExpect(jsonPath("$.nextState", is("LOCKED")));

        Game locked = gameRepository.findById(UUID.fromString(game.gameId())).orElseThrow();
        assertThat(locked.getState()).isEqualTo(GameStates.LOCKED);

        mockMvc.perform(post("/api/games/" + game.gameId() + "/rounds/1/buy")
                        .header("Authorization", "Bearer " + game.aliceToken())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shopSlot\":1}"))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.error", is("LOCKED")));

        mockMvc.perform(post("/api/games/" + game.gameId() + "/rounds/1/relocate")
                        .header("Authorization", "Bearer " + game.aliceToken())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"unitId":"%s","to":{"type":"LANE","slot":0}}
                                """.formatted(unitId)))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.error", is("LOCKED")));

        // Seed deliberately different remaining offers — random seeds can collide.
        // Arrays.asList (not List.of): slot 0 is sold/null after both players bought.
        UUID gameUuid = UUID.fromString(game.gameId());
        Round round = roundRepository.findByGameIdAndRoundNumber(gameUuid, 1).orElseThrow();
        List<String> aliceOffers = Arrays.asList(null, "Squire", "Mage");
        List<String> bobOffers = Arrays.asList(null, "Ranger", "Knight");
        shopService.persistOffers(round.getId(), game.aliceId(), aliceOffers);
        shopService.persistOffers(round.getId(), game.bobId(), bobOffers);

        MvcResult bobState = mockMvc.perform(get("/api/games/" + game.gameId() + "/state")
                        .header("Authorization", "Bearer " + game.bobToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shop", hasSize(3)))
                .andReturn();
        MvcResult aliceState = mockMvc.perform(get("/api/games/" + game.gameId() + "/state")
                        .header("Authorization", "Bearer " + game.aliceToken()))
                .andExpect(status().isOk())
                .andReturn();

        List<String> aliceShop = shopUnitTypes(aliceState);
        List<String> bobShop = shopUnitTypes(bobState);
        assertThat(aliceShop).containsExactlyElementsOf(aliceOffers);
        assertThat(bobShop).containsExactlyElementsOf(bobOffers);
        // Opponent board never exposed as yourBoard for Bob (Alice's unit is not Bob's board).
        assertThat(bobState.getResponse().getContentAsString()).doesNotContain(unitId);
    }

    @Test
    void refresh_costsOneGold_replacesAllShopSlots() throws Exception {
        TestAuthSupport.JoinedGame game = TestAuthSupport.joinTwoPlayers(mockMvc, "cmd_refresh");
        UUID gameUuid = UUID.fromString(game.gameId());
        List<String> expectedOffers = ShopGenerator.generateOfferTypes(
                gameUuid, 1, game.aliceId(), 1);

        MvcResult refreshResult = mockMvc.perform(post("/api/games/" + game.gameId() + "/rounds/1/refresh")
                        .header("Authorization", "Bearer " + game.aliceToken())
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.gold", is(9)))
                .andExpect(jsonPath("$.shop", hasSize(3)))
                .andExpect(jsonPath("$.shop[0].unitType", notNullValue()))
                .andExpect(jsonPath("$.shop[1].unitType", notNullValue()))
                .andExpect(jsonPath("$.shop[2].unitType", notNullValue()))
                .andReturn();

        assertThat(shopUnitTypes(refreshResult)).containsExactlyElementsOf(expectedOffers);

        Round round = roundRepository.findByGameIdAndRoundNumber(gameUuid, 1).orElseThrow();
        List<String> persisted = shopOfferRepository
                .findByRoundIdAndPlayerIdOrderBySlotAsc(round.getId(), game.aliceId())
                .stream()
                .map(ShopOffer::getUnitType)
                .toList();
        assertThat(persisted).containsExactlyElementsOf(expectedOffers);
    }

    @Test
    void sell_afterBuy_refundsGoldAndRemovesUnitFromLane() throws Exception {
        TestAuthSupport.JoinedGame game = TestAuthSupport.joinTwoPlayers(mockMvc, "cmd_sell");
        int cost = shopSlotCost(game.gameId(), game.aliceToken(), 0);

        MvcResult buyResult = mockMvc.perform(post("/api/games/" + game.gameId() + "/rounds/1/buy")
                        .header("Authorization", "Bearer " + game.aliceToken())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shopSlot\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gold", is(10 - cost)))
                .andReturn();

        String unitId = objectMapper.readTree(buyResult.getResponse().getContentAsString())
                .path("lane")
                .get(0)
                .path("unitId")
                .asText();

        mockMvc.perform(post("/api/games/" + game.gameId() + "/rounds/1/sell")
                        .header("Authorization", "Bearer " + game.aliceToken())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"unitId\":\"" + unitId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.gold", is(10)))
                .andExpect(jsonPath("$.lane[0].unitId", nullValue()));
    }

    private UUID alicePlanId(TestAuthSupport.JoinedGame game) {
        UUID roundId = roundRepository
                .findByGameIdAndRoundNumber(UUID.fromString(game.gameId()), 1)
                .orElseThrow()
                .getId();
        RoundPlan plan = roundPlanRepository
                .findByRoundIdAndPlayerId(roundId, game.aliceId())
                .orElseThrow();
        return plan.getId();
    }

    private int shopSlotCost(String gameId, String token, int slot) throws Exception {
        MvcResult state = mockMvc.perform(get("/api/games/" + gameId + "/state")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shop[" + slot + "].cost", notNullValue()))
                .andReturn();
        return objectMapper.readTree(state.getResponse().getContentAsString())
                .path("shop").get(slot).path("cost").asInt();
    }

    private List<String> shopUnitTypes(MvcResult result) throws Exception {
        JsonNode shop = objectMapper.readTree(result.getResponse().getContentAsString()).path("shop");
        // Arrays.asList: sold slots are null (List.of rejects null elements).
        return Arrays.asList(
                textOrNull(shop.get(0).path("unitType")),
                textOrNull(shop.get(1).path("unitType")),
                textOrNull(shop.get(2).path("unitType")));
    }

    private static String textOrNull(JsonNode node) {
        return node.isNull() || node.isMissingNode() ? null : node.asText();
    }
}
