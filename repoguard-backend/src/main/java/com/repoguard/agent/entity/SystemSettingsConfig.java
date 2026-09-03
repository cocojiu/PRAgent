package com.repoguard.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("system_settings_config")
public class SystemSettingsConfig {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String systemName;
    private String language;
    private String timezone;
    private Integer retentionDays;
    private Integer maxDiffLines;
    private Boolean autoComment;
    private Boolean autoRetry;
    private Boolean githubComment;
    private Boolean highRiskPr;
    private Boolean failedTask;
    private String notificationEmail;
    private Boolean webhookSignature;
    private Boolean secretMasking;
    private Boolean publicRepoAllowed;
    private Integer tokenTtlDays;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public String getSystemName() {
        return systemName;
    }

    public void setSystemName(String systemName) {
        this.systemName = systemName;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    public Integer getRetentionDays() {
        return retentionDays;
    }

    public void setRetentionDays(Integer retentionDays) {
        this.retentionDays = retentionDays;
    }

    public Integer getMaxDiffLines() {
        return maxDiffLines;
    }

    public void setMaxDiffLines(Integer maxDiffLines) {
        this.maxDiffLines = maxDiffLines;
    }

    public Boolean getAutoComment() {
        return autoComment;
    }

    public void setAutoComment(Boolean autoComment) {
        this.autoComment = autoComment;
    }

    public Boolean getAutoRetry() {
        return autoRetry;
    }

    public void setAutoRetry(Boolean autoRetry) {
        this.autoRetry = autoRetry;
    }

    public Boolean getGithubComment() {
        return githubComment;
    }

    public void setGithubComment(Boolean githubComment) {
        this.githubComment = githubComment;
    }

    public Boolean getHighRiskPr() {
        return highRiskPr;
    }

    public void setHighRiskPr(Boolean highRiskPr) {
        this.highRiskPr = highRiskPr;
    }

    public Boolean getFailedTask() {
        return failedTask;
    }

    public void setFailedTask(Boolean failedTask) {
        this.failedTask = failedTask;
    }

    public String getNotificationEmail() {
        return notificationEmail;
    }

    public void setNotificationEmail(String notificationEmail) {
        this.notificationEmail = notificationEmail;
    }

    public Boolean getWebhookSignature() {
        return webhookSignature;
    }

    public void setWebhookSignature(Boolean webhookSignature) {
        this.webhookSignature = webhookSignature;
    }

    public Boolean getSecretMasking() {
        return secretMasking;
    }

    public void setSecretMasking(Boolean secretMasking) {
        this.secretMasking = secretMasking;
    }

    public Boolean getPublicRepoAllowed() {
        return publicRepoAllowed;
    }

    public void setPublicRepoAllowed(Boolean publicRepoAllowed) {
        this.publicRepoAllowed = publicRepoAllowed;
    }

    public Integer getTokenTtlDays() {
        return tokenTtlDays;
    }

    public void setTokenTtlDays(Integer tokenTtlDays) {
        this.tokenTtlDays = tokenTtlDays;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
