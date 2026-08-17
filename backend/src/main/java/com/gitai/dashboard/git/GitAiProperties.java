package com.gitai.dashboard.git;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "git-ai")
public class GitAiProperties {
    /** Directory where bare repository mirrors are stored when a repository has no explicit mirror path. */
    private String mirrorRoot = "./data/mirrors";
    /** Git AI's bin directory. It is prepended to PATH for child git processes. */
    private String binDirectory = System.getProperty("user.home") + "/.git-ai/bin";
    private Duration commandTimeout = Duration.ofMinutes(2);
    /** Initial mirror clones transfer every object, so they are allowed a separate, longer timeout. */
    private Duration cloneTimeout = Duration.ofMinutes(15);
    /** Maximum number of commits processed by one click/API call. Keeps sync work bounded for very large repositories. */
    private int maxCommitsPerSync = 500;
    /** Hard protection against unexpectedly large command output. */
    private long maxCommandOutputBytes = 64L * 1024 * 1024;

    public String getMirrorRoot() { return mirrorRoot; }
    public void setMirrorRoot(String mirrorRoot) { this.mirrorRoot = mirrorRoot; }
    public String getBinDirectory() { return binDirectory; }
    public void setBinDirectory(String binDirectory) { this.binDirectory = binDirectory; }
    public Duration getCommandTimeout() { return commandTimeout; }
    public void setCommandTimeout(Duration commandTimeout) { this.commandTimeout = commandTimeout; }
    public Duration getCloneTimeout() { return cloneTimeout; }
    public void setCloneTimeout(Duration cloneTimeout) { this.cloneTimeout = cloneTimeout; }
    public int getMaxCommitsPerSync() { return maxCommitsPerSync; }
    public void setMaxCommitsPerSync(int maxCommitsPerSync) { this.maxCommitsPerSync = Math.max(1, maxCommitsPerSync); }
    public long getMaxCommandOutputBytes() { return maxCommandOutputBytes; }
    public void setMaxCommandOutputBytes(long maxCommandOutputBytes) { this.maxCommandOutputBytes = Math.max(1024, maxCommandOutputBytes); }
}
