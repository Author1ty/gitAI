package com.gitai.dashboard.sync;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "git-ai.sync")
public class SyncJobProperties {
    /** Keep Git/clone load intentionally conservative; large repositories are processed in bounded batches. */
    private int workerCount = 1;
    private int queueCapacity = 10;

    public int getWorkerCount() { return workerCount; }
    public void setWorkerCount(int workerCount) { this.workerCount = Math.max(1, workerCount); }
    public int getQueueCapacity() { return queueCapacity; }
    public void setQueueCapacity(int queueCapacity) { this.queueCapacity = Math.max(1, queueCapacity); }
}
