package com.repoguard.agent.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("review_execution_attempt")
public class ReviewExecutionAttempt {

    @TableId
    private Long id;
    private Long taskId;
    private Integer attemptNo;
    private Long generation;
    private String commitSha;
    private String inputFingerprint;
    private String claimId;
    private String workerId;
    private String status;
    private String failureCategory;
    private String budgetExhaustedStage;
    private Long policyVersion;
    private String promptVersion;
    private String contextVersion;
    private String schemaVersion;
    private String verifierVersion;
    private String aggregationVersion;
    private Long diffFetchMs;
    private Long reviewMs;
    private Long persistMs;
    private Long totalMs;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    private BigDecimal estimatedCost;
    private LocalDateTime queuedAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime payloadPurgedAt;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public Integer getAttemptNo() { return attemptNo; }
    public void setAttemptNo(Integer attemptNo) { this.attemptNo = attemptNo; }
    public Long getGeneration() { return generation; }
    public void setGeneration(Long generation) { this.generation = generation; }
    public String getCommitSha() { return commitSha; }
    public void setCommitSha(String commitSha) { this.commitSha = commitSha; }
    public String getInputFingerprint() { return inputFingerprint; }
    public void setInputFingerprint(String inputFingerprint) { this.inputFingerprint = inputFingerprint; }
    public String getClaimId() { return claimId; }
    public void setClaimId(String claimId) { this.claimId = claimId; }
    public String getWorkerId() { return workerId; }
    public void setWorkerId(String workerId) { this.workerId = workerId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getFailureCategory() { return failureCategory; }
    public void setFailureCategory(String failureCategory) { this.failureCategory = failureCategory; }
    public String getBudgetExhaustedStage() { return budgetExhaustedStage; }
    public void setBudgetExhaustedStage(String budgetExhaustedStage) { this.budgetExhaustedStage = budgetExhaustedStage; }
    public Long getPolicyVersion() { return policyVersion; }
    public void setPolicyVersion(Long policyVersion) { this.policyVersion = policyVersion; }
    public String getPromptVersion() { return promptVersion; }
    public void setPromptVersion(String promptVersion) { this.promptVersion = promptVersion; }
    public String getContextVersion() { return contextVersion; }
    public void setContextVersion(String contextVersion) { this.contextVersion = contextVersion; }
    public String getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(String schemaVersion) { this.schemaVersion = schemaVersion; }
    public String getVerifierVersion() { return verifierVersion; }
    public void setVerifierVersion(String verifierVersion) { this.verifierVersion = verifierVersion; }
    public String getAggregationVersion() { return aggregationVersion; }
    public void setAggregationVersion(String aggregationVersion) { this.aggregationVersion = aggregationVersion; }
    public Long getDiffFetchMs() { return diffFetchMs; }
    public void setDiffFetchMs(Long diffFetchMs) { this.diffFetchMs = diffFetchMs; }
    public Long getReviewMs() { return reviewMs; }
    public void setReviewMs(Long reviewMs) { this.reviewMs = reviewMs; }
    public Long getPersistMs() { return persistMs; }
    public void setPersistMs(Long persistMs) { this.persistMs = persistMs; }
    public Long getTotalMs() { return totalMs; }
    public void setTotalMs(Long totalMs) { this.totalMs = totalMs; }
    public Integer getPromptTokens() { return promptTokens; }
    public void setPromptTokens(Integer promptTokens) { this.promptTokens = promptTokens; }
    public Integer getCompletionTokens() { return completionTokens; }
    public void setCompletionTokens(Integer completionTokens) { this.completionTokens = completionTokens; }
    public Integer getTotalTokens() { return totalTokens; }
    public void setTotalTokens(Integer totalTokens) { this.totalTokens = totalTokens; }
    public BigDecimal getEstimatedCost() { return estimatedCost; }
    public void setEstimatedCost(BigDecimal estimatedCost) { this.estimatedCost = estimatedCost; }
    public LocalDateTime getQueuedAt() { return queuedAt; }
    public void setQueuedAt(LocalDateTime queuedAt) { this.queuedAt = queuedAt; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
    public LocalDateTime getPayloadPurgedAt() { return payloadPurgedAt; }
    public void setPayloadPurgedAt(LocalDateTime payloadPurgedAt) { this.payloadPurgedAt = payloadPurgedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
