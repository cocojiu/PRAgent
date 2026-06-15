package com.repoguard.agent.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("notification_channel_binding")
public class NotificationChannelBinding {

    @TableId
    private Long id;
    private String name;
    private String provider;
    private String organization;
    private String repository;
    private Boolean enabled;
    private String webhookUrlValue;
    private String secretValue;
    private Boolean notifyReviewCompleted;
    private Boolean notifyReviewFailed;
    private Boolean notifyHumanReviewRequired;
    private Boolean notifyGithubComment;
    private String status;
    private LocalDateTime lastCheckedAt;
    private String lastError;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getOrganization() { return organization; }
    public void setOrganization(String organization) { this.organization = organization; }
    public String getRepository() { return repository; }
    public void setRepository(String repository) { this.repository = repository; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public String getWebhookUrlValue() { return webhookUrlValue; }
    public void setWebhookUrlValue(String webhookUrlValue) { this.webhookUrlValue = webhookUrlValue; }
    public String getSecretValue() { return secretValue; }
    public void setSecretValue(String secretValue) { this.secretValue = secretValue; }
    public Boolean getNotifyReviewCompleted() { return notifyReviewCompleted; }
    public void setNotifyReviewCompleted(Boolean notifyReviewCompleted) { this.notifyReviewCompleted = notifyReviewCompleted; }
    public Boolean getNotifyReviewFailed() { return notifyReviewFailed; }
    public void setNotifyReviewFailed(Boolean notifyReviewFailed) { this.notifyReviewFailed = notifyReviewFailed; }
    public Boolean getNotifyHumanReviewRequired() { return notifyHumanReviewRequired; }
    public void setNotifyHumanReviewRequired(Boolean notifyHumanReviewRequired) { this.notifyHumanReviewRequired = notifyHumanReviewRequired; }
    public Boolean getNotifyGithubComment() { return notifyGithubComment; }
    public void setNotifyGithubComment(Boolean notifyGithubComment) { this.notifyGithubComment = notifyGithubComment; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getLastCheckedAt() { return lastCheckedAt; }
    public void setLastCheckedAt(LocalDateTime lastCheckedAt) { this.lastCheckedAt = lastCheckedAt; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
