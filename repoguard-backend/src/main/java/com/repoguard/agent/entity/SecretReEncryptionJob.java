package com.repoguard.agent.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("secret_re_encryption_job")
public class SecretReEncryptionJob {

    @TableId
    private Long id;
    private String mode;
    private String status;
    private String sourceKeyId;
    private String targetKeyId;
    private String sourceKeyCiphertext;
    private String targetKeyCiphertext;
    private String currentTable;
    private Long checkpointId;
    private Integer batchSize;
    private Long scannedCount;
    private Long reEncryptedCount;
    private Long skippedCount;
    private Long failedCount;
    private Integer retryCount;
    private LocalDateTime nextRetryAt;
    private String claimedBy;
    private LocalDateTime claimedAt;
    private LocalDateTime leaseUntil;
    private String lastFailureReason;
    private String lastFailureMessage;
    private Long createdByUserId;
    private String createdByUsername;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime completedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getSourceKeyId() {
        return sourceKeyId;
    }

    public void setSourceKeyId(String sourceKeyId) {
        this.sourceKeyId = sourceKeyId;
    }

    public String getTargetKeyId() {
        return targetKeyId;
    }

    public void setTargetKeyId(String targetKeyId) {
        this.targetKeyId = targetKeyId;
    }

    public String getSourceKeyCiphertext() {
        return sourceKeyCiphertext;
    }

    public void setSourceKeyCiphertext(String sourceKeyCiphertext) {
        this.sourceKeyCiphertext = sourceKeyCiphertext;
    }

    public String getTargetKeyCiphertext() {
        return targetKeyCiphertext;
    }

    public void setTargetKeyCiphertext(String targetKeyCiphertext) {
        this.targetKeyCiphertext = targetKeyCiphertext;
    }

    public String getCurrentTable() {
        return currentTable;
    }

    public void setCurrentTable(String currentTable) {
        this.currentTable = currentTable;
    }

    public Long getCheckpointId() {
        return checkpointId;
    }

    public void setCheckpointId(Long checkpointId) {
        this.checkpointId = checkpointId;
    }

    public Integer getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(Integer batchSize) {
        this.batchSize = batchSize;
    }

    public Long getScannedCount() {
        return scannedCount;
    }

    public void setScannedCount(Long scannedCount) {
        this.scannedCount = scannedCount;
    }

    public Long getReEncryptedCount() {
        return reEncryptedCount;
    }

    public void setReEncryptedCount(Long reEncryptedCount) {
        this.reEncryptedCount = reEncryptedCount;
    }

    public Long getSkippedCount() {
        return skippedCount;
    }

    public void setSkippedCount(Long skippedCount) {
        this.skippedCount = skippedCount;
    }

    public Long getFailedCount() {
        return failedCount;
    }

    public void setFailedCount(Long failedCount) {
        this.failedCount = failedCount;
    }

    public Integer getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(Integer retryCount) {
        this.retryCount = retryCount;
    }

    public LocalDateTime getNextRetryAt() {
        return nextRetryAt;
    }

    public void setNextRetryAt(LocalDateTime nextRetryAt) {
        this.nextRetryAt = nextRetryAt;
    }

    public String getClaimedBy() {
        return claimedBy;
    }

    public void setClaimedBy(String claimedBy) {
        this.claimedBy = claimedBy;
    }

    public LocalDateTime getClaimedAt() {
        return claimedAt;
    }

    public void setClaimedAt(LocalDateTime claimedAt) {
        this.claimedAt = claimedAt;
    }

    public LocalDateTime getLeaseUntil() {
        return leaseUntil;
    }

    public void setLeaseUntil(LocalDateTime leaseUntil) {
        this.leaseUntil = leaseUntil;
    }

    public String getLastFailureReason() {
        return lastFailureReason;
    }

    public void setLastFailureReason(String lastFailureReason) {
        this.lastFailureReason = lastFailureReason;
    }

    public String getLastFailureMessage() {
        return lastFailureMessage;
    }

    public void setLastFailureMessage(String lastFailureMessage) {
        this.lastFailureMessage = lastFailureMessage;
    }

    public Long getCreatedByUserId() {
        return createdByUserId;
    }

    public void setCreatedByUserId(Long createdByUserId) {
        this.createdByUserId = createdByUserId;
    }

    public String getCreatedByUsername() {
        return createdByUsername;
    }

    public void setCreatedByUsername(String createdByUsername) {
        this.createdByUsername = createdByUsername;
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

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(LocalDateTime completedAt) {
        this.completedAt = completedAt;
    }
}
