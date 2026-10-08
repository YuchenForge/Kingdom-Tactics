package com.kingdom.combined;

import com.kingdom.api.KingdomTacticsApplication;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/** One web process with API security, migrations, and scheduled round processing. */
@SpringBootConfiguration
@Import(KingdomTacticsApplication.class)
@ComponentScan(basePackages = {"com.kingdom.worker.job", "com.kingdom.worker.service", "com.kingdom.worker.mapper"})
@EntityScan("com.kingdom.api.entity")
@EnableJpaRepositories("com.kingdom.api.repository")
public class CombinedApplication {
    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(CombinedApplication.class);
        // Never load the standalone worker's application.yml or its security exclusions.
        application.setDefaultProperties(Map.of("spring.config.name", "combined"));
        application.run(args);
    }
}
