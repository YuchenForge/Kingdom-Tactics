package com.kingdom.api.game;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
class GameLifecycleIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("kingdom_tactics_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.jwt.secret", () -> "test-jwt-secret-at-least-32-bytes!");
        registry.add("app.jwt.expiration-ms", () -> "3600000");
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void createJoinStateAndAuthzFlow() throws Exception {
        String tokenAlice = register("alice", "alice@test.com");
        String tokenBob = register("bob", "bob@test.com");
        String tokenCharlie = register("charlie", "charlie@test.com");
        String tokenCarol = register("carol", "carol@test.com");

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

        String gameId = extractJsonField(createResult.getResponse().getContentAsString(), "gameId");
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
                .andExpect(jsonPath("$.yourBoard", hasSize(4)))
                .andExpect(jsonPath("$.yourBoard[0]", hasSize(4)))
                .andExpect(jsonPath("$.yourLane", hasSize(5)))
                .andExpect(jsonPath("$.shop").isArray())
                .andExpect(jsonPath("$.shop", hasSize(0)))
                .andExpect(jsonPath("$.opponentUnitCount").value(0))
                .andExpect(jsonPath("$.opponentBoard").doesNotExist());

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

    private String register(String username, String email) throws Exception {
        String body = """
                {
                  "username": "%s",
                  "email": "%s",
                  "password": "password123"
                }
                """.formatted(username, email);

        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token", notNullValue()))
                .andReturn();

        return extractJsonField(result.getResponse().getContentAsString(), "token");
    }

    private static String extractJsonField(String json, String field) {
        String marker = "\"" + field + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) {
            throw new IllegalStateException("Field not found: " + field);
        }
        start += marker.length();
        int end = json.indexOf('"', start);
        return json.substring(start, end);
    }
}
