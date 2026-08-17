package com.gitai.dashboard.migration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the explicit, one-time H2 to MySQL business-data import.
 * This process is deliberately disabled unless the h2-import Spring profile and the confirmation phrase are supplied.
 */
@ConfigurationProperties(prefix = "git-ai.h2-import")
public class H2ImportProperties {
    public static final String CONFIRMATION_PHRASE = "IMPORT_EXISTING_DATA_WITHOUT_SYNC";

    private boolean enabled;
    private String confirm;
    private String sourceUrl;
    private String sourceUsername = "sa";
    private String sourcePassword = "";
    private int batchSize = 1_000;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getConfirm() { return confirm; }
    public void setConfirm(String confirm) { this.confirm = confirm; }
    public String getSourceUrl() { return sourceUrl; }
    public void setSourceUrl(String sourceUrl) { this.sourceUrl = sourceUrl; }
    public String getSourceUsername() { return sourceUsername; }
    public void setSourceUsername(String sourceUsername) { this.sourceUsername = sourceUsername; }
    public String getSourcePassword() { return sourcePassword; }
    public void setSourcePassword(String sourcePassword) { this.sourcePassword = sourcePassword; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
}
