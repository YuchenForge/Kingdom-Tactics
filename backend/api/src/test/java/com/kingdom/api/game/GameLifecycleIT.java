package com.kingdom.api.game;

import com.kingdom.api.support.AbstractPostgresIT;
import com.kingdom.api.support.TestAuthSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GameLifecycleIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void createJoinStateAndAuthzFlow() throws Exception {
        String tokenAlice = TestAuthSupport.register(mockMvc, "alice", "alice@test.com");
        String tokenBob = TestAuthSupport.register(mockMvc, "bob", "bob@test.com");
        String tokenCharlie = TestAuthSupport.register(mockMvc, "charlie", "charlie@test.com");
        String tokenCarol = TestAuthSupport.register(mockMvc, "carol", "carol@test.com");

        mockMvc.perform(post("/api/games"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));

        MvcResult createResult = mockMvc.perform(post("/api/games")
                        .header("Authorization", "Bearer " + tokenAlice))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.gameId", notNullValue()))
                .andExpect(jsonPath("$.state").value("WAITING_FOR_PLAYERS"))
                .andExpect(jsonPath("$.currentRound").value(0))
                .andExpect(jsonPath("$.players", hasSize(1)))
                .andExpect(jsonPath("$.players[0].seat").value(0))
                .andExpect(jsonPath("$.players[0].gold").value(10))
                .andExpect(jsonPath("$.players[0].playerId").exists())
                .andExpect(jsonPath("$.players[0].username").value("alice"))
                .andExpect(jsonPath("$.createdAt").exists())
                .andReturn();

        String gameId = TestAuthSupport.extractJsonField(createResult.getResponse().getContentAsString(), "gameId");
        assertThat(gameId).isNotBlank();

        mockMvc.perform(post("/api/games/" + gameId + "/join")
                        .header("Authorization", "Bearer " + tokenBob))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gameId").exists())
                .andExpect(jsonPath("$.state").value("PREPARATION"))
                .andExpect(jsonPath("$.currentRound").value(1))
                .andExpect(jsonPath("$.players", hasSize(2)))
                .andExpect(jsonPath("$.players[0].seat").value(0))
                .andExpect(jsonPath("$.players[0].gold").value(10))
                .andExpect(jsonPath("$.players[1].seat").value(1))
                .andExpect(jsonPath("$.players[1].gold").value(10))
                .andExpect(jsonPath("$.planningDeadline", notNullValue()));

        mockMvc.perform(get("/api/games/" + gameId + "/state")
                        .header("Authorization", "Bearer " + tokenAlice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gameId").exists())
                .andExpect(jsonPath("$.yourSeat").value(0))
                .andExpect(jsonPath("$.yourGold").value(10))
                .andExpect(jsonPath("$.yourKeepHp").value(20))
                .andExpect(jsonPath("$.opponentKeepHp").value(20))
                .andExpect(jsonPath("$.isLocked").value(false))
                .andExpect(jsonPath("$.opponentIsLocked").value(false))
                .andExpect(jsonPath("$.yourBoard", hasSize(4)))
                .andExpect(jsonPath("$.yourBoard[0]", hasSize(4)))
                .andExpect(jsonPath("$.yourLane", hasSize(5)))
                .andExpect(jsonPath("$.shop").isArray())
                .andExpect(jsonPath("$.shop", hasSize(3)))
                .andExpect(jsonPath("$.shop[0].slot").value(0))
                .andExpect(jsonPath("$.shop[0].unitType").isString())
                .andExpect(jsonPath("$.shop[0].cost").isNumber())
                .andExpect(jsonPath("$.shop[1].slot").value(1))
                .andExpect(jsonPath("$.shop[1].unitType").isString())
                .andExpect(jsonPath("$.shop[1].cost").isNumber())
                .andExpect(jsonPath("$.shop[2].slot").value(2))
                .andExpect(jsonPath("$.shop[2].unitType").isString())
                .andExpect(jsonPath("$.shop[2].cost").isNumber())
                .andExpect(jsonPath("$.opponentUnitCount").value(0))
                .andExpect(jsonPath("$.opponentBoard").doesNotExist())
                .andExpect(jsonPath("$.opponentShop").doesNotExist())
                .andExpect(jsonPath("$.opponentLane").doesNotExist());

        mockMvc.perform(get("/api/games/" + gameId + "/state")
                        .header("Authorization", "Bearer " + tokenBob))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.yourSeat").value(1))
                .andExpect(jsonPath("$.shop", hasSize(3)))
                .andExpect(jsonPath("$.shop[0].slot").value(0))
                .andExpect(jsonPath("$.shop[0].unitType").isString())
                .andExpect(jsonPath("$.shop[0].cost").isNumber());

        mockMvc.perform(get("/api/games/" + gameId + "/state")
                        .header("Authorization", "Bearer " + tokenCharlie))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("NOT_GAME_PARTICIPANT"));

        mockMvc.perform(post("/api/games/" + gameId + "/join")
                        .header("Authorization", "Bearer " + tokenBob))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("ALREADY_IN_GAME"));

        mockMvc.perform(post("/api/games/" + gameId + "/join")
                        .header("Authorization", "Bearer " + tokenCarol))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("GAME_FULL"));
    }

    @Test
    void joinMissingGameReturns404() throws Exception {
        String token = TestAuthSupport.register(mockMvc, "alice", "alice@test.com");

        mockMvc.perform(post("/api/games/" + UUID.randomUUID() + "/join")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("GAME_NOT_FOUND"));
    }

    @Test
    void getStateWhileWaitingReturns409() throws Exception {
        String aliceToken = TestAuthSupport.register(mockMvc, "alice", "alice@test.com");
        String gameId = TestAuthSupport.createGame(mockMvc, aliceToken);

        mockMvc.perform(get("/api/games/" + gameId + "/state")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("GAME_NOT_READY"));
    }

    @Test
    void creatorJoiningOwnGameReturns400() throws Exception {
        String aliceToken = TestAuthSupport.register(mockMvc, "alice", "alice@test.com");
        String gameId = TestAuthSupport.createGame(mockMvc, aliceToken);

        mockMvc.perform(post("/api/games/" + gameId + "/join")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("ALREADY_IN_GAME"));
    }
}
