package com.gitai.dashboard.migration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/** The migration process must not start the periodic synchronization scheduler. */
@Configuration
@Profile("!h2-import")
@ConditionalOnProperty(prefix = "git-ai.sync", name = "scheduler-enabled", havingValue = "true", matchIfMissing = true)
@EnableScheduling
public class SchedulingConfiguration {
}