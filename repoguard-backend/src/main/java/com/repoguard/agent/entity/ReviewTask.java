package com.repoguard.agent.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("review_task")
public class ReviewTask {

    @TableId
    private Long id;
    private Integer prNumber;
    private String title;
    private String repository;
    private String organization;
    private String commitSha;
    private String branchName;
    private String status;
    private String riskLevel;
    private Integer mqRetries;
    private Integer publishAttempts;
    private LocalDateTime nextPublishRetryAt;
    private String lastPublishError;
    private LocalDateTime publishClaimedAt;
    private String publishClaimedBy;
    private LocalDateTime reviewClaimedAt;
    private String reviewClaimedBy;
    private String llmStatus;
    private String llmProvider;
    private String llmModel;
    private Integer llmDurationMs;
    private String llmParseStatus;
    private String llmFallbackReason;
    private String llmPromptSummary;
    private Integer llmPromptTokens;
    private Integer llmCompletionTokens;
    private Integer llmTotalTokens;
    private BigDecimal llmEstimatedCost;
    private String prUrl;
    /** 任务首次创建来源，例如手动输入或 GitHub PR 选择器。 */
    private String source;
    /** 本次触发来源，用于标记复用已有任务等再次触发场景。 */
    private String triggerSource;
    private Boolean humanReviewRequired;
    private String humanReviewStatus;
    private String humanReviewNote;
    private String humanReviewBy;
    private LocalDateTime humanReviewedAt;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Integer durationSeconds;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Integer getPrNumber() {
        return prNumber;
    }

    public void setPrNumber(Integer prNumber) {
        this.prNumber = prNumber;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getRepository() {
        return repository;
    }

    public void setRepository(String repository) {
        this.repository = repository;
    }

    public String getOrganization() {
        return organization;
    }

    public void setOrganization(String organization) {
        this.organization = organization;
    }

    public String getCommitSha() {
        return commitSha;
    }

    public void setCommitSha(String commitSha) {
        this.commitSha = commitSha;
    }

    public String getBranchName() {
        return branchName;
    }

    public void setBranchName(String branchName) {
        this.branchName = branchName;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getRiskLevel() {
        return riskLevel;
    }

    public void setRiskLevel(String riskLevel) {
        this.riskLevel = riskLevel;
    }

    public Integer getMqRetries() {
        return mqRetries;
    }

    public void setMqRetries(Integer mqRetries) {
        this.mqRetries = mqRetries;
    }

    public Integer getPublishAttempts() {
        return publishAttempts;
    }

    public void setPublishAttempts(Integer publishAttempts) {
        this.publishAttempts = publishAttempts;
    }

    public LocalDateTime getNextPublishRetryAt() {
        return nextPublishRetryAt;
    }

    public void setNextPublishRetryAt(LocalDateTime nextPublishRetryAt) {
        this.nextPublishRetryAt = nextPublishRetryAt;
    }

    public String getLastPublishError() {
        return lastPublishError;
    }

    public void setLastPublishError(String lastPublishError) {
        this.lastPublishError = lastPublishError;
    }

    public LocalDateTime getPublishClaimedAt() {
        return publishClaimedAt;
    }

    public void setPublishClaimedAt(LocalDateTime publishClaimedAt) {
        this.publishClaimedAt = publishClaimedAt;
    }

    public String getPublishClaimedBy() {
        return publishClaimedBy;
    }

    public void setPublishClaimedBy(String publishClaimedBy) {
        this.publishClaimedBy = publishClaimedBy;
    }

    public LocalDateTime getReviewClaimedAt() {
        return reviewClaimedAt;
    }

    public void setReviewClaimedAt(LocalDateTime reviewClaimedAt) {
        this.reviewClaimedAt = reviewClaimedAt;
    }

    public String getReviewClaimedBy() {
        return reviewClaimedBy;
    }

    public void setReviewClaimedBy(String reviewClaimedBy) {
        this.reviewClaimedBy = reviewClaimedBy;
    }

    public String getLlmStatus() {
        return llmStatus;
    }

    public void setLlmStatus(String llmStatus) {
        this.llmStatus = llmStatus;
    }

    public String getLlmProvider() {
        return llmProvider;
    }

    public void setLlmProvider(String llmProvider) {
        this.llmProvider = llmProvider;
    }

    public String getLlmModel() {
        return llmModel;
    }

    public void setLlmModel(String llmModel) {
        this.llmModel = llmModel;
    }

    public Integer getLlmDurationMs() {
        return llmDurationMs;
    }

    public void setLlmDurationMs(Integer llmDurationMs) {
        this.llmDurationMs = llmDurationMs;
    }

    public String getLlmParseStatus() {
        return llmParseStatus;
    }

    public void setLlmParseStatus(String llmParseStatus) {
        this.llmParseStatus = llmParseStatus;
    }

    public String getLlmFallbackReason() {
        return llmFallbackReason;
    }

    public void setLlmFallbackReason(String llmFallbackReason) {
        this.llmFallbackReason = llmFallbackReason;
    }

    public String getLlmPromptSummary() {
        return llmPromptSummary;
    }

    public void setLlmPromptSummary(String llmPromptSummary) {
        this.llmPromptSummary = llmPromptSummary;
    }

    public Integer getLlmPromptTokens() {
        return llmPromptTokens;
    }

    public void setLlmPromptTokens(Integer llmPromptTokens) {
        this.llmPromptTokens = llmPromptTokens;
    }

    public Integer getLlmCompletionTokens() {
        return llmCompletionTokens;
    }

    public void setLlmCompletionTokens(Integer llmCompletionTokens) {
        this.llmCompletionTokens = llmCompletionTokens;
    }

    public Integer getLlmTotalTokens() {
        return llmTotalTokens;
    }

    public void setLlmTotalTokens(Integer llmTotalTokens) {
        this.llmTotalTokens = llmTotalTokens;
    }

    public BigDecimal getLlmEstimatedCost() {
        return llmEstimatedCost;
    }

    public void setLlmEstimatedCost(BigDecimal llmEstimatedCost) {
        this.llmEstimatedCost = llmEstimatedCost;
    }

    public String getPrUrl() {
        return prUrl;
    }

    public void setPrUrl(String prUrl) {
        this.prUrl = prUrl;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getTriggerSource() {
        return triggerSource;
    }

    public void setTriggerSource(String triggerSource) {
        this.triggerSource = triggerSource;
    }

    public Boolean getHumanReviewRequired() {
        return humanReviewRequired;
    }

    public void setHumanReviewRequired(Boolean humanReviewRequired) {
        this.humanReviewRequired = humanReviewRequired;
    }

    public String getHumanReviewStatus() {
        return humanReviewStatus;
    }

    public void setHumanReviewStatus(String humanReviewStatus) {
        this.humanReviewStatus = humanReviewStatus;
    }

    public String getHumanReviewNote() {
        return humanReviewNote;
    }

    public void setHumanReviewNote(String humanReviewNote) {
        this.humanReviewNote = humanReviewNote;
    }

    public String getHumanReviewBy() {
        return humanReviewBy;
    }

    public void setHumanReviewBy(String humanReviewBy) {
        this.humanReviewBy = humanReviewBy;
    }

    public LocalDateTime getHumanReviewedAt() {
        return humanReviewedAt;
    }

    public void setHumanReviewedAt(LocalDateTime humanReviewedAt) {
        this.humanReviewedAt = humanReviewedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public LocalDateTime getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(LocalDateTime finishedAt) {
        this.finishedAt = finishedAt;
    }

    public Integer getDurationSeconds() {
        return durationSeconds;
    }

    public void setDurationSeconds(Integer durationSeconds) {
        this.durationSeconds = durationSeconds;
    }
}
