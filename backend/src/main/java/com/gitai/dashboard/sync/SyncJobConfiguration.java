package com.gitai.dashboard.sync;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class SyncJobConfiguration {
    @Bean("syncJobExecutor")
    public Executor syncJobExecutor(SyncJobProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("git-ai-sync-");
        executor.setCorePoolSize(properties.getWorkerCount());
        executor.setMaxPoolSize(properties.getWorkerCount());
        executor.setQueueCapacity(properties.getQueueCapacity());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
