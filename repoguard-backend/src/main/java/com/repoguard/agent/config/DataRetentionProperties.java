package com.repoguard.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "repoguard.data-retention")
public class DataRetentionProperties {

    private long cleanupLeaseMinutes = 30;
    private int cleanupMaxTasksPerRun = 500;

    public long getCleanupLeaseMinutes() {
        return cleanupLeaseMinutes;
    }

    public void setCleanupLeaseMinutes(long cleanupLeaseMinutes) {
        this.cleanupLeaseMinutes = cleanupLeaseMinutes;
    }

    public int getCleanupMaxTasksPerRun() {
        return cleanupMaxTasksPerRun;
    }

    public void setCleanupMaxTasksPerRun(int cleanupMaxTasksPerRun) {
        this.cleanupMaxTasksPerRun = cleanupMaxTasksPerRun;
    }

    public long normalizedCleanupLeaseMinutes() {
        return cleanupLeaseMinutes <= 0 ? 30 : cleanupLeaseMinutes;
    }

    public int normalizedCleanupMaxTasksPerRun() {
        if (cleanupMaxTasksPerRun <= 0) {
            return 500;
        }
        return Math.min(cleanupMaxTasksPerRun, 5000);
    }
}
