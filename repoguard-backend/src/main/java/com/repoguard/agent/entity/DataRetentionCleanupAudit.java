package com.repoguard.agent.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("data_retention_cleanup_audit")
public class DataRetentionCleanupAudit {

    @TableId
    private Long id;
    private String mode;
    private String status;
    private Integer retentionDays;
    private Integer maxTasks;
    private String backupReference;
    private LocalDateTime cutoffTime;
    private Long candidateTasks;
    private Integer selectedTasks;
    private Integer deletedBatchItems;
    private Integer deletedPublications;
    private Integer deletedBatches;
    private Integer deletedChangedFiles;
    private Integer deletedTimelines;
    private Integer deletedFindings;
    private Integer deletedTasks;
    private String failureReason;
    private String failureMessage;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
    private LocalDateTime updatedAt;

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

    public Integer getRetentionDays() {
        return retentionDays;
    }

    public void setRetentionDays(Integer retentionDays) {
        this.retentionDays = retentionDays;
    }

    public Integer getMaxTasks() {
        return maxTasks;
    }

    public void setMaxTasks(Integer maxTasks) {
        this.maxTasks = maxTasks;
    }

    public String getBackupReference() {
        return backupReference;
    }

    public void setBackupReference(String backupReference) {
        this.backupReference = backupReference;
    }

    public LocalDateTime getCutoffTime() {
        return cutoffTime;
    }

    public void setCutoffTime(LocalDateTime cutoffTime) {
        this.cutoffTime = cutoffTime;
    }

    public Long getCandidateTasks() {
        return candidateTasks;
    }

    public void setCandidateTasks(Long candidateTasks) {
        this.candidateTasks = candidateTasks;
    }

    public Integer getSelectedTasks() {
        return selectedTasks;
    }

    public void setSelectedTasks(Integer selectedTasks) {
        this.selectedTasks = selectedTasks;
    }

    public Integer getDeletedBatchItems() {
        return deletedBatchItems;
    }

    public void setDeletedBatchItems(Integer deletedBatchItems) {
        this.deletedBatchItems = deletedBatchItems;
    }

    public Integer getDeletedPublications() {
        return deletedPublications;
    }

    public void setDeletedPublications(Integer deletedPublications) {
        this.deletedPublications = deletedPublications;
    }

    public Integer getDeletedBatches() {
        return deletedBatches;
    }

    public void setDeletedBatches(Integer deletedBatches) {
        this.deletedBatches = deletedBatches;
    }

    public Integer getDeletedChangedFiles() {
        return deletedChangedFiles;
    }

    public void setDeletedChangedFiles(Integer deletedChangedFiles) {
        this.deletedChangedFiles = deletedChangedFiles;
    }

    public Integer getDeletedTimelines() {
        return deletedTimelines;
    }

    public void setDeletedTimelines(Integer deletedTimelines) {
        this.deletedTimelines = deletedTimelines;
    }

    public Integer getDeletedFindings() {
        return deletedFindings;
    }

    public void setDeletedFindings(Integer deletedFindings) {
        this.deletedFindings = deletedFindings;
    }

    public Integer getDeletedTasks() {
        return deletedTasks;
    }

    public void setDeletedTasks(Integer deletedTasks) {
        this.deletedTasks = deletedTasks;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public String getFailureMessage() {
        return failureMessage;
    }

    public void setFailureMessage(String failureMessage) {
        this.failureMessage = failureMessage;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(LocalDateTime completedAt) {
        this.completedAt = completedAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
