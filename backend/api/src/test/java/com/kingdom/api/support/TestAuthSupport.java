package com.kingdom.api.support;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public final class TestAuthSupport {

    private TestAuthSupport() {
    }

    public static String register(MockMvc mockMvc, String username, String email) throws Exception {
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

    public static String createGame(MockMvc mockMvc, String token) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/games")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.gameId", notNullValue()))
                .andReturn();

        return extractJsonField(result.getResponse().getContentAsString(), "gameId");
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
}
