package com.kingdom.worker.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.BeforeEach;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Shared Postgres for worker ITs. No class-level @Transactional —
 * claim tests need real commits so SKIP LOCKED / second-claim see durable state.
 *
 * One container is started once and reused for the whole JVM/test run 
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(AbstractPostgresIT.ClockConfig.class)
public abstract class AbstractPostgresIT {

    @Autowired
    protected MutableClock testClock;

    @BeforeEach
    void resetTestClock() {
        testClock.set(Instant.now().truncatedTo(ChronoUnit.MILLIS));
    }

    @TestConfiguration
    public static class ClockConfig {
        @Bean
        @Primary
        MutableClock testClock() { return new MutableClock(); }
    }

    public static class MutableClock extends Clock {
        private volatile Instant now = Instant.now();
        public void set(Instant now) { this.now = now; }
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
    }

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("kingdom_tactics_worker_test")
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
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("app.worker.enabled", () -> "false");
    }
}
