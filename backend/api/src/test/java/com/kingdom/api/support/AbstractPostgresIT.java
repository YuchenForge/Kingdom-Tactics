package com.kingdom.api.support;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Shared Postgres Testcontainers setup for integration tests.
 * <p>
 * Starts the container in a static initializer (not {@code @Container}) so subclasses
 * share one live instance — JUnit's {@code @Container} lifecycle would stop it after
 * the first test class finishes. {@code @Transactional} rolls back DB state between tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
public abstract class AbstractPostgresIT {

    public static final String TEST_JWT_SECRET = "test-jwt-secret-at-least-32-bytes!";

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("kingdom_tactics_test")
            .withUsername("test")
            .withPassword("test");

    static {
        postgres.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.jwt.secret", () -> TEST_JWT_SECRET);
        registry.add("app.jwt.expiration-ms", () -> "3600000");
        registry.add("app.scheduling.enabled", () -> "false");
    }
}
