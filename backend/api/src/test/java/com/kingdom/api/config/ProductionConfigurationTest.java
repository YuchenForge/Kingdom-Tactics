package com.kingdom.api.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.assertj.core.api.Assertions.*;

class ProductionConfigurationTest {
    private MockEnvironment production() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        return environment;
    }

    @Test
    void permitsExactHttpsOriginsAndBearerCommandHeaders() {
        var environment = production().withProperty("app.cors.allowed-origins", "https://game.example, https://preview.example");
        var source = new WebCorsConfiguration().corsConfigurationSource(environment);
        var config = source.getCorsConfiguration(new MockHttpServletRequest("OPTIONS", "/api/games"));
        assertThat(config.checkOrigin("https://game.example")).isEqualTo("https://game.example");
        assertThat(config.checkOrigin("https://evil.example")).isNull();
        assertThat(config.getAllowedHeaders()).contains("Authorization", "Content-Type", "Idempotency-Key");
        assertThat(config.getAllowCredentials()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "*", "https://*.example.com", "http://game.example", "https://game.example/", "https://game.example/path", "https://user@game.example", "https://game.example?query=1"})
    void rejectsInvalidProductionOrigins(String origin) {
        var environment = production().withProperty("app.cors.allowed-origins", origin);
        assertThatThrownBy(() -> new WebCorsConfiguration().corsConfigurationSource(environment))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void developmentDoesNotRequireCrossOriginAccess() {
        assertThatCode(() -> new WebCorsConfiguration().corsConfigurationSource(new MockEnvironment()))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "short", "${JWT_SECRET}", "dev-secret-change-in-production!", "local-compose-only-secret-change-before-production-1234567890"})
    void rejectsMissingWeakOrKnownLocalSecrets(String secret) {
        assertThatThrownBy(() -> new ProductionConfiguration(new JwtProperties(secret, 86400000), production()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsPrivateSecretAndRejectsMixedProfiles() {
        var environment = production();
        var jwt = new JwtProperties("test-private-production-key-123456789012345", 86400000);
        assertThatCode(() -> new ProductionConfiguration(jwt, environment)).doesNotThrowAnyException();
        environment.setActiveProfiles("prod", "dev");
        assertThatThrownBy(() -> new ProductionConfiguration(jwt, environment)).isInstanceOf(IllegalArgumentException.class);
    }
}
