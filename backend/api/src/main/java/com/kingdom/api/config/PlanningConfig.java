package com.kingdom.api.config;

import com.kingdom.engine.planning.CommandApplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class PlanningConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public CommandApplier commandApplier() {
        return new CommandApplier();
    }
}
