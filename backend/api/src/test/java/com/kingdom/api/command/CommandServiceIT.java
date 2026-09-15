package com.kingdom.api.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kingdom.api.entity.Command;
import com.kingdom.api.entity.Game;
import com.kingdom.api.entity.GameStates;
import com.kingdom.api.entity.RoundPlan;
import com.kingdom.api.repository.CommandRepository;
import com.kingdom.api.repository.GameRepository;
import com.kingdom.api.repository.RoundPlanRepository;
import com.kingdom.api.repository.RoundRepository;
import com.kingdom.api.support.AbstractPostgresIT;
import com.kingdom.api.support.TestAuthSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

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

    @Test
    void buy_thenIdempotentRetry_sameGold_oneCommandRow() throws Exception {
        JoinedGame game = joinTwoPlayers("idem");
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
        JoinedGame game = joinTwoPlayers("nokey");

        mockMvc.perform(post("/api/games/" + game.gameId() + "/rounds/1/buy")
                        .header("Authorization", "Bearer " + game.aliceToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shopSlot\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("VALIDATION_ERROR")));
    }

    @Test
    void planningFlow_relocateLockBoth_thenBuyRejectedAndShopHidden() throws Exception {
        JoinedGame game = joinTwoPlayers("flow");
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

        // Bob's GET /state shop is his own offers — never Alice's.
        MvcResult bobState = mockMvc.perform(get("/api/games/" + game.gameId() + "/state")
                        .header("Authorization", "Bearer " + game.bobToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shop", hasSize(3)))
                .andReturn();
        MvcResult aliceState = mockMvc.perform(get("/api/games/" + game.gameId() + "/state")
                        .header("Authorization", "Bearer " + game.aliceToken()))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode aliceShop = objectMapper.readTree(aliceState.getResponse().getContentAsString()).path("shop");
        JsonNode bobShop = objectMapper.readTree(bobState.getResponse().getContentAsString()).path("shop");
        // Alice bought slot 0 earlier; Bob bought slot 0 too — remaining offers still differ by player seed.
        assertThat(bobShop.toString()).isNotEqualTo(aliceShop.toString());
        // Opponent board never exposed as yourBoard for Bob (Alice's unit is not Bob's board).
        assertThat(bobState.getResponse().getContentAsString()).doesNotContain(unitId);
    }

    private UUID alicePlanId(JoinedGame game) {
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

    private JoinedGame joinTwoPlayers(String suffix) throws Exception {
        MvcResult aliceReg = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"cmd_a_%s","email":"cmd_a_%s@test.com","password":"password123"}
                                """.formatted(suffix, suffix)))
                .andExpect(status().isCreated())
                .andReturn();
        String aliceBody = aliceReg.getResponse().getContentAsString();
        String aliceToken = TestAuthSupport.extractJsonField(aliceBody, "token");
        UUID aliceId = UUID.fromString(TestAuthSupport.extractJsonField(aliceBody, "userId"));

        MvcResult bobReg = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"cmd_b_%s","email":"cmd_b_%s@test.com","password":"password123"}
                                """.formatted(suffix, suffix)))
                .andExpect(status().isCreated())
                .andReturn();
        String bobBody = bobReg.getResponse().getContentAsString();
        String bobToken = TestAuthSupport.extractJsonField(bobBody, "token");
        UUID bobId = UUID.fromString(TestAuthSupport.extractJsonField(bobBody, "userId"));

        String gameId = TestAuthSupport.createGame(mockMvc, aliceToken);
        mockMvc.perform(post("/api/games/" + gameId + "/join")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk());

        return new JoinedGame(gameId, aliceId, bobId, aliceToken, bobToken);
    }

    private record JoinedGame(
            String gameId,
            UUID aliceId,
            UUID bobId,
            String aliceToken,
            String bobToken) {
    }
}
