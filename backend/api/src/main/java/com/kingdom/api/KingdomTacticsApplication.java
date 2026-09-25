package com.kingdom.api;

import com.kingdom.api.config.JwtProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties(JwtProperties.class)
@EnableScheduling
public class KingdomTacticsApplication {

    public static void main(String[] args) {
        SpringApplication.run(KingdomTacticsApplication.class, args);
    }
}
