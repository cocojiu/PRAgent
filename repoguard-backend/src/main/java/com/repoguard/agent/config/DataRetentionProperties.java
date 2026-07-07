package com.repoguard.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "repoguard.data-retention")
public class DataRetentionProperties {

    private long cleanupLeaseMinutes = 30;

    public long getCleanupLeaseMinutes() {
        return cleanupLeaseMinutes;
    }

    public void setCleanupLeaseMinutes(long cleanupLeaseMinutes) {
        this.cleanupLeaseMinutes = cleanupLeaseMinutes;
    }

    public long normalizedCleanupLeaseMinutes() {
        return cleanupLeaseMinutes <= 0 ? 30 : cleanupLeaseMinutes;
    }
}
