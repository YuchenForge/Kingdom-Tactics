package com.kingdom.combined;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = CombinedApplication.class, properties = {
        "spring.config.name=combined", "app.worker.poll-ms=100", "app.scheduling.enabled=true"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CombinedApplicationIT {
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");
    static { postgres.start(); }
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.jwt.secret", () -> "combined-test-secret-at-least-32-bytes");
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ApplicationContext context;

    private JsonNode postJson(String path, String body, String token) throws Exception {
        var request = post(path).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", UUID.randomUUID().toString());
        if (body != null) request.content(body);
        if (token != null) request.header("Authorization", "Bearer " + token);
        var result = mvc.perform(request).andExpect(status().is2xxSuccessful()).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }
    private String register() throws Exception {
        String username = "combined" + UUID.randomUUID().toString().substring(0, 8);
        return postJson("/api/auth/register", json.writeValueAsString(java.util.Map.of(
                "username", username, "email", username + "@example.com", "password", "test-password123")), null)
                .get("token").asText();
    }
    @Test
    void secureApiMigratesAndScheduledWorkerAdvancesRoundInSameProcess() throws Exception {
        assertThat(context.getBeansOfType(Clock.class)).hasSize(1);
        assertThat(context.getBean(ThreadPoolTaskScheduler.class).getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(2);
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/health/readiness")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        String first = register();
        String second = register();
        String game = postJson("/api/games", null, first).get("gameId").asText();
        postJson("/api/games/" + game + "/join", null, second);
        for (String token : new String[]{first, second}) postJson("/api/games/" + game + "/rounds/1/lock", null, token);
        // Real scheduled discovery, resolution and presentation timing; no manual worker calls.
        await().atMost(30, SECONDS).untilAsserted(() -> mvc.perform(get("/api/games/" + game + "/state")
                .header("Authorization", "Bearer " + first)).andExpect(status().isOk())
                .andExpect(jsonPath("$.currentRound").value(2)));
        mvc.perform(get("/api/games/" + game + "/rounds/1/result").header("Authorization", "Bearer " + second))
                .andExpect(status().isOk());
    }
}
