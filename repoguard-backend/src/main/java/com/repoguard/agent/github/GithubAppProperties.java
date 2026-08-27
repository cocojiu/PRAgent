package com.repoguard.agent.github;

import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "repoguard.github-app")
public class GithubAppProperties {

    private boolean enabled;
    private Long appId;
    private String privateKey;
    private Set<Long> allowedInstallationIds = new LinkedHashSet<>();
    private int tokenRefreshSkewSeconds = 90;
    private String apiVersion = "2022-11-28";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Long getAppId() {
        return appId;
    }

    public void setAppId(Long appId) {
        this.appId = appId;
    }

    public String getPrivateKey() {
        return privateKey;
    }

    public void setPrivateKey(String privateKey) {
        this.privateKey = privateKey;
    }

    public Set<Long> getAllowedInstallationIds() {
        return allowedInstallationIds;
    }

    public void setAllowedInstallationIds(Set<Long> allowedInstallationIds) {
        this.allowedInstallationIds = allowedInstallationIds == null ? new LinkedHashSet<>() : allowedInstallationIds;
    }

    public int getTokenRefreshSkewSeconds() {
        return tokenRefreshSkewSeconds;
    }

    public void setTokenRefreshSkewSeconds(int tokenRefreshSkewSeconds) {
        this.tokenRefreshSkewSeconds = tokenRefreshSkewSeconds;
    }

    public String getApiVersion() {
        return apiVersion;
    }

    public void setApiVersion(String apiVersion) {
        this.apiVersion = apiVersion;
    }
}
