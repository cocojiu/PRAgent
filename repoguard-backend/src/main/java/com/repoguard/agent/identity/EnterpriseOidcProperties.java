package com.repoguard.agent.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "repoguard.enterprise-oidc")
public class EnterpriseOidcProperties {

    private boolean enabled;
    private String issuerUri;
    private String audience;
    private String requiredAmr = "mfa";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getIssuerUri() {
        return issuerUri;
    }

    public void setIssuerUri(String issuerUri) {
        this.issuerUri = issuerUri;
    }

    public String getAudience() {
        return audience;
    }

    public void setAudience(String audience) {
        this.audience = audience;
    }

    public String getRequiredAmr() {
        return requiredAmr;
    }

    public void setRequiredAmr(String requiredAmr) {
        this.requiredAmr = requiredAmr;
    }
}
