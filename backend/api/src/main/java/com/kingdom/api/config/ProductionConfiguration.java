package com.kingdom.api.config;

import java.nio.charset.StandardCharsets;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

@Configuration
@Profile("prod")
public class ProductionConfiguration {
    public ProductionConfiguration(JwtProperties jwt, Environment environment) {
        if (environment.acceptsProfiles(Profiles.of("dev", "test"))) {
            throw new IllegalArgumentException("Use the prod profile alone, without dev or test");
        }
        String secret = jwt.secret();
        if (secret == null || secret.isBlank() || secret.contains("${")
                || secret.getBytes(StandardCharsets.UTF_8).length < 32
                || secret.equals("dev-secret-change-in-production!")
                || secret.equals("local-compose-only-secret-change-before-production-1234567890")) {
            throw new IllegalArgumentException("Production requires a private JWT_SECRET of at least 32 UTF-8 bytes");
        }
    }
}
