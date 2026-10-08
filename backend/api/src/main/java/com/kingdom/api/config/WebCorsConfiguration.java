package com.kingdom.api.config;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class WebCorsConfiguration {
    @Bean
    public UrlBasedCorsConfigurationSource corsConfigurationSource(Environment environment) {
        List<String> origins = Arrays.stream(environment.getProperty("app.cors.allowed-origins", "").split(","))
                .map(String::trim).filter(value -> !value.isEmpty()).toList();
        boolean production = environment.acceptsProfiles(Profiles.of("prod"));
        if (production && origins.isEmpty()) {
            throw new IllegalArgumentException("Production requires CORS_ALLOWED_ORIGINS");
        }
        for (String origin : origins) {
            URI uri;
            try {
                uri = URI.create(origin);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("CORS origins must be exact HTTP(S) origins");
            }
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                    || uri.getFragment() != null || !uri.getPath().isEmpty()
                    || !("https".equals(uri.getScheme()) || !production && "http".equals(uri.getScheme()))) {
                throw new IllegalArgumentException("CORS origins must be exact origins; production requires HTTPS");
            }
        }
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(origins);
        cors.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key"));
        cors.setExposedHeaders(List.of("X-Request-Id"));
        // Authentication uses explicit bearer tokens, not cross-site cookies.
        cors.setAllowCredentials(false);
        cors.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return source;
    }
}
