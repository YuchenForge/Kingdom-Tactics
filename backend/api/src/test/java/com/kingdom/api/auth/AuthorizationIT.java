package com.kingdom.api.auth;

import com.kingdom.api.config.JwtProperties;
import com.kingdom.api.security.JwtService;
import com.kingdom.api.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthorizationIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mockMvc;

    private final String gamePath = "/api/games/" + UUID.randomUUID();

    @Test
    void gameWithoutTokenReturns401() throws Exception {
        mockMvc.perform(get(gamePath))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    @Test
    void gameWithInvalidTokenReturns401() throws Exception {
        mockMvc.perform(get(gamePath)
                        .header("Authorization", "Bearer not-a-valid-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    @Test
    void expiredJwtReturns401() throws Exception {
        JwtService shortLived = new JwtService(new JwtProperties(TEST_JWT_SECRET, -1_000));
        String expiredToken = shortLived.generateToken(UUID.randomUUID());

        mockMvc.perform(get("/api/me")
                        .header("Authorization", "Bearer " + expiredToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    @Test
    void validJwtForMissingUserReturns401() throws Exception {
        JwtService jwt = new JwtService(new JwtProperties(TEST_JWT_SECRET, 3_600_000));
        String token = jwt.generateToken(UUID.randomUUID());

        mockMvc.perform(get("/api/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }
}
