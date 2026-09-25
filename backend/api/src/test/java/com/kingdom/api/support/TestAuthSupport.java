package com.kingdom.api.support;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public final class TestAuthSupport {

    private TestAuthSupport() {
    }

    public static String register(MockMvc mockMvc, String username, String email) throws Exception {
        return registerUser(mockMvc, username, email).token();
    }

    public static RegisteredUser registerUser(MockMvc mockMvc, String username, String email)
            throws Exception {
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
                .andExpect(jsonPath("$.userId", notNullValue()))
                .andReturn();

        String json = result.getResponse().getContentAsString();
        return new RegisteredUser(
                UUID.fromString(extractJsonField(json, "userId")),
                extractJsonField(json, "token"));
    }

    public static String createGame(MockMvc mockMvc, String token) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/games")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.gameId", notNullValue()))
                .andReturn();

        return extractJsonField(result.getResponse().getContentAsString(), "gameId");
    }

    /**
     * Register two players, create a game as Alice, and join as Bob.
     * Caller owns transaction policy ({@code @Transactional} vs NOT_SUPPORTED).
     */
    public static JoinedGame joinTwoPlayers(MockMvc mockMvc, String prefix) throws Exception {
        RegisteredUser alice = registerUser(
                mockMvc, prefix + "_a", prefix + "_a@test.com");
        RegisteredUser bob = registerUser(
                mockMvc, prefix + "_b", prefix + "_b@test.com");

        String gameId = createGame(mockMvc, alice.token());
        mockMvc.perform(post("/api/games/" + gameId + "/join")
                        .header("Authorization", "Bearer " + bob.token()))
                .andExpect(status().isOk());

        return new JoinedGame(gameId, alice.userId(), bob.userId(), alice.token(), bob.token());
    }

    public static String extractJsonField(String json, String field) {
        String marker = "\"" + field + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) {
            throw new IllegalStateException("Field not found: " + field);
        }
        start += marker.length();
        int end = json.indexOf('"', start);
        return json.substring(start, end);
    }

    public record RegisteredUser(UUID userId, String token) {
    }

    public record JoinedGame(
            String gameId,
            UUID aliceId,
            UUID bobId,
            String aliceToken,
            String bobToken) {
    }
}
