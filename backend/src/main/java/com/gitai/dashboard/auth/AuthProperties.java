package com.gitai.dashboard.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "git-ai.auth")
public class AuthProperties {
    private String provider = "placeholder";
    private boolean placeholderEnabled = true;
    private Duration sessionTtl = Duration.ofHours(8);

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public boolean isPlaceholderEnabled() { return placeholderEnabled; }
    public void setPlaceholderEnabled(boolean placeholderEnabled) { this.placeholderEnabled = placeholderEnabled; }
    public Duration getSessionTtl() { return sessionTtl; }
    public void setSessionTtl(Duration sessionTtl) { this.sessionTtl = sessionTtl; }
}
