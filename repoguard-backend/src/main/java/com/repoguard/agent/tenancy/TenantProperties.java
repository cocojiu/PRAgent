package com.repoguard.agent.tenancy;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "repoguard.tenancy")
public class TenantProperties {

    private boolean enabled;
    private String headerName = "X-RepoGuard-Tenant";

    /** Resolves the explicit personal default without weakening enterprise isolation. */
    public long currentTenantId() {
        return enabled ? TenantContext.requireTenantId() : TenantContext.currentTenantIdOrDefault();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getHeaderName() {
        return headerName;
    }

    public void setHeaderName(String headerName) {
        this.headerName = headerName;
    }
}
