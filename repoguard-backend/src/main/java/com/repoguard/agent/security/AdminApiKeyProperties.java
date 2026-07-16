package com.repoguard.agent.security;

import com.repoguard.agent.config.RuntimeProfilePolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.security.admin-api-key")
public class AdminApiKeyProperties {

    private boolean enabled = true;
    private String key = "";
    private String headerName = "X-RepoGuard-Admin-Key";

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

    public boolean isProtectionActive() {
        return enabled && hasConfiguredKey();
    }

    public void validateForProfiles(String[] activeProfiles) {
        boolean productionProfile = RuntimeProfilePolicy.isProductionLike(activeProfiles);
        if (productionProfile && !hasConfiguredKey()) {
            throw new IllegalStateException("app.security.admin-api-key.key must be configured in a production-like profile");
        }
    }

    private boolean hasConfiguredKey() {
        return key != null && !key.isBlank();
    }
}
