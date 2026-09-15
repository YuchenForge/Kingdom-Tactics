package com.kingdom.api.config;

import com.kingdom.engine.planning.CommandApplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PlanningConfig {

    @Bean
    public CommandApplier commandApplier() {
        return new CommandApplier();
    }
}
