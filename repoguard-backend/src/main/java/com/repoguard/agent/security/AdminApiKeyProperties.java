package com.repoguard.agent.security;

import com.repoguard.agent.config.RuntimeProfilePolicy;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.security.admin-api-key")
public class AdminApiKeyProperties {

    private boolean enabled = true;
    private String key = "";
    private String headerName = "X-RepoGuard-Admin-Key";
    private int minKeyLength = 32;
    private int failedRequestsPerMinutePerIp = 30;
    private int maxTrackedClients = 10_000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getHeaderName() {
        return headerName;
    }

    public void setHeaderName(String headerName) {
        this.headerName = headerName;
    }

    public int getMinKeyLength() {
        return minKeyLength;
    }

    public void setMinKeyLength(int minKeyLength) {
        this.minKeyLength = minKeyLength;
    }

    public int getFailedRequestsPerMinutePerIp() {
        return failedRequestsPerMinutePerIp;
    }

    public void setFailedRequestsPerMinutePerIp(int failedRequestsPerMinutePerIp) {
        this.failedRequestsPerMinutePerIp = failedRequestsPerMinutePerIp;
    }

    public int getMaxTrackedClients() {
        return maxTrackedClients;
    }

    public void setMaxTrackedClients(int maxTrackedClients) {
        this.maxTrackedClients = maxTrackedClients;
    }

    public boolean isProtectionActive() {
        return enabled && hasConfiguredKey();
    }

    public void validateForProfiles(String[] activeProfiles) {
        if (minKeyLength < 32) {
            throw new IllegalStateException("app.security.admin-api-key.min-key-length must be at least 32");
        }
        if (failedRequestsPerMinutePerIp <= 0) {
            throw new IllegalStateException(
                "app.security.admin-api-key.failed-requests-per-minute-per-ip must be positive"
            );
        }
        if (maxTrackedClients <= 0) {
            throw new IllegalStateException("app.security.admin-api-key.max-tracked-clients must be positive");
        }
        boolean productionProfile = RuntimeProfilePolicy.isProductionLike(activeProfiles);
        if (productionProfile && !hasConfiguredKey()) {
            throw new IllegalStateException("app.security.admin-api-key.key must be configured in a production-like profile");
        }
        if (productionProfile && !hasStrongKey()) {
            throw new IllegalStateException(
                "app.security.admin-api-key.key must contain at least "
                    + minKeyLength
                    + " non-placeholder characters in a production-like profile"
            );
        }
    }

    private boolean hasConfiguredKey() {
        return key != null && !key.isBlank();
    }

    private boolean hasStrongKey() {
        if (!hasConfiguredKey()) {
            return false;
        }
        String normalized = key.trim().toLowerCase(Locale.ROOT);
        return normalized.length() >= minKeyLength
            && !normalized.contains("change-me")
            && !normalized.contains("changeme")
            && !normalized.contains("replace-before");
    }
}
