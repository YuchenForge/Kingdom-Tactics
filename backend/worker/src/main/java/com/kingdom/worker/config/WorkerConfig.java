package com.kingdom.worker.config;

import com.kingdom.api.service.ShopService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.time.Clock;

@Configuration
@Import(ShopService.class)
public class WorkerConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
