package com.repoguard.agent.github.checks;

import com.repoguard.agent.config.RuntimeProfilePolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@ConfigurationProperties(prefix = "app.github.check-run")
public class GithubCheckRunProperties {

    private boolean enabled;
    private String name = "RepoGuard PR Review";
    private int recoveryIntervalMs = 5000;
    private int recoveryBatchSize = 20;
    private int claimLeaseSeconds = 120;
    private int retryBaseSeconds = 10;
    private int retryMaxSeconds = 900;
    private int annotationLimit = 50;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getRecoveryIntervalMs() {
        return recoveryIntervalMs;
    }

    public void setRecoveryIntervalMs(int recoveryIntervalMs) {
        this.recoveryIntervalMs = recoveryIntervalMs;
    }

    public int getRecoveryBatchSize() {
        return recoveryBatchSize;
    }

    public void setRecoveryBatchSize(int recoveryBatchSize) {
        this.recoveryBatchSize = recoveryBatchSize;
    }

    public int getClaimLeaseSeconds() {
        return claimLeaseSeconds;
    }

    public void setClaimLeaseSeconds(int claimLeaseSeconds) {
        this.claimLeaseSeconds = claimLeaseSeconds;
    }

    public int getRetryBaseSeconds() {
        return retryBaseSeconds;
    }

    public void setRetryBaseSeconds(int retryBaseSeconds) {
        this.retryBaseSeconds = retryBaseSeconds;
    }

    public int getRetryMaxSeconds() {
        return retryMaxSeconds;
    }

    public void setRetryMaxSeconds(int retryMaxSeconds) {
        this.retryMaxSeconds = retryMaxSeconds;
    }

    public int getAnnotationLimit() {
        return annotationLimit;
    }

    public void setAnnotationLimit(int annotationLimit) {
        this.annotationLimit = annotationLimit;
    }

    public void validateForProfiles(String[] activeProfiles) {
        if (!StringUtils.hasText(name) || name.trim().length() > 100) {
            throw new IllegalStateException("app.github.check-run.name must contain 1-100 characters");
        }
        if (recoveryIntervalMs <= 0 || recoveryBatchSize <= 0 || claimLeaseSeconds <= 0) {
            throw new IllegalStateException("GitHub Check Run recovery settings must be positive");
        }
        if (retryBaseSeconds <= 0 || retryMaxSeconds < retryBaseSeconds) {
            throw new IllegalStateException("GitHub Check Run retry settings are invalid");
        }
        if (annotationLimit < 1 || annotationLimit > 50) {
            throw new IllegalStateException("app.github.check-run.annotation-limit must be between 1 and 50");
        }
        if (enabled && RuntimeProfilePolicy.isProductionLike(activeProfiles)
            && !StringUtils.hasText(name)) {
            throw new IllegalStateException("GitHub Check Run name is required in a production-like profile");
        }
    }
}
