package com.kingdom.api.security;

import com.kingdom.api.config.JwtProperties;
import io.jsonwebtoken.ExpiredJwtException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String TEST_SECRET = "test-jwt-secret-at-least-32-bytes!";

    @Test
    void generateTokenAndParseUserId() {
        JwtService jwtService = new JwtService(new JwtProperties(TEST_SECRET, 3_600_000));

        UUID userId = UUID.randomUUID();
        String token = jwtService.generateToken(userId);

        assertThat(jwtService.parseUserId(token)).isEqualTo(userId);
    }

    @Test
    void expiredTokenThrows() {
        JwtService jwtService = new JwtService(new JwtProperties(TEST_SECRET, -1_000));

        String token = jwtService.generateToken(UUID.randomUUID());

        assertThatThrownBy(() -> jwtService.parseUserId(token))
                .isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    void expirationSecondsMatchesConfiguredDuration() {
        JwtService jwtService = new JwtService(new JwtProperties(TEST_SECRET, 86_400_000));

        assertThat(jwtService.expirationSeconds()).isEqualTo(86_400);
    }
}
