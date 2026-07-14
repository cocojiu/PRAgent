package com.repoguard.agent.github.webhook;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@ConfigurationProperties(prefix = "app.github.webhook")
public class GithubWebhookProperties {

    public static final int DEFAULT_MAX_PAYLOAD_BYTES = 1024 * 1024;

    private boolean enabled = true;
    private String secret;
    private boolean requireSignature = true;
    private boolean ignoreDraft = true;
    private int maxPayloadBytes = DEFAULT_MAX_PAYLOAD_BYTES;
    private int maxRequestsPerMinutePerIp = 120;
    private int maxRequestsPerMinutePerRepository = 60;
    private List<String> allowedActions = new ArrayList<>(List.of("opened", "reopened", "synchronize", "ready_for_review"));
    private List<String> allowedRepositories = new ArrayList<>();
    private List<String> allowedHeadBranches = new ArrayList<>(List.of("PRAgent-test"));

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public boolean isRequireSignature() {
        return requireSignature;
    }

    public void setRequireSignature(boolean requireSignature) {
        this.requireSignature = requireSignature;
    }

    public boolean isIgnoreDraft() {
        return ignoreDraft;
    }

    public void setIgnoreDraft(boolean ignoreDraft) {
        this.ignoreDraft = ignoreDraft;
    }

    public int getMaxPayloadBytes() {
        return maxPayloadBytes;
    }

    public void setMaxPayloadBytes(int maxPayloadBytes) {
        this.maxPayloadBytes = maxPayloadBytes;
    }

    public int getMaxRequestsPerMinutePerIp() {
        return maxRequestsPerMinutePerIp;
    }

    public void setMaxRequestsPerMinutePerIp(int maxRequestsPerMinutePerIp) {
        this.maxRequestsPerMinutePerIp = maxRequestsPerMinutePerIp;
    }

    public int getMaxRequestsPerMinutePerRepository() {
        return maxRequestsPerMinutePerRepository;
    }

    public void setMaxRequestsPerMinutePerRepository(int maxRequestsPerMinutePerRepository) {
        this.maxRequestsPerMinutePerRepository = maxRequestsPerMinutePerRepository;
    }

    public List<String> getAllowedActions() {
        return allowedActions;
    }

    public void setAllowedActions(List<String> allowedActions) {
        this.allowedActions = allowedActions == null ? new ArrayList<>() : new ArrayList<>(allowedActions);
    }

    public List<String> getAllowedRepositories() {
        return allowedRepositories;
    }

    public void setAllowedRepositories(List<String> allowedRepositories) {
        this.allowedRepositories = allowedRepositories == null ? new ArrayList<>() : new ArrayList<>(allowedRepositories);
    }

    public List<String> getAllowedHeadBranches() {
        return allowedHeadBranches;
    }

    public void setAllowedHeadBranches(List<String> allowedHeadBranches) {
        this.allowedHeadBranches = allowedHeadBranches == null ? new ArrayList<>() : new ArrayList<>(allowedHeadBranches);
    }

    public void validateForProfiles(String[] activeProfiles) {
        if (maxPayloadBytes <= 0) {
            throw new IllegalStateException("app.github.webhook.max-payload-bytes must be positive");
        }
        if (maxRequestsPerMinutePerIp <= 0 || maxRequestsPerMinutePerRepository <= 0) {
            throw new IllegalStateException("app.github.webhook rate limits must be positive");
        }
        boolean productionProfile = Arrays.stream(activeProfiles)
            .anyMatch(profile -> "prod".equalsIgnoreCase(profile));
        if (!productionProfile || !enabled) {
            return;
        }
        if (!requireSignature) {
            throw new IllegalStateException("app.github.webhook.require-signature must be true in prod profile");
        }
        if (!StringUtils.hasText(secret)) {
            throw new IllegalStateException("app.github.webhook.secret must be configured in prod profile");
        }
        if (allowedRepositories.stream().noneMatch(StringUtils::hasText)) {
            throw new IllegalStateException("app.github.webhook.allowed-repositories must not be empty in prod profile");
        }
    }
}
