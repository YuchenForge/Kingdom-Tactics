package com.kingdom.api.auth;

import com.kingdom.api.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@TestPropertySource(properties = "app.cors.allowed-origins=https://game.example")
class ProductionHttpIT extends AbstractPostgresIT {
    @Autowired private MockMvc mvc;

    @Test
    void allowsPreflightForBearerCommandsFromConfiguredOrigin() throws Exception {
        mvc.perform(options("/api/games")
                .header("Origin", "https://game.example")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "authorization,content-type,idempotency-key"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://game.example"));
    }

    @Test
    void rejectsUntrustedOriginAndStillRequiresAuthentication() throws Exception {
        mvc.perform(options("/api/games").header("Origin", "https://evil.example")
                .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        mvc.perform(get("/api/me").header("Origin", "https://game.example"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://game.example"));
    }

    @Test
    void healthProbesArePublicAndDoNotExposeComponents() throws Exception {
        for (String path : new String[]{"/health", "/health/readiness", "/health/liveness"}) {
            mvc.perform(get(path)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UP"))
                    .andExpect(jsonPath("$.components").doesNotExist());
        }
        mvc.perform(get("/env")).andExpect(status().isUnauthorized());
    }
}
