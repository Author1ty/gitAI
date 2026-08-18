package com.gitai.dashboard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

import java.time.Clock;
import org.springframework.core.env.Profiles;

@SpringBootApplication
@ConfigurationPropertiesScan
public class GitAiDashboardApplication {
    @Bean
    Clock serverClock() {
        return Clock.systemDefaultZone();
    }

    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(GitAiDashboardApplication.class, args);
        // The one-time importer is deliberately a non-web process and exits as soon as it has copied and validated data.
        if (context.getEnvironment().acceptsProfiles(Profiles.of("h2-import"))) {
            System.exit(SpringApplication.exit(context));
        }
    }
}