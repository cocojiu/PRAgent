package com.repoguard.agent.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("review_task_archive_summary")
public class ReviewTaskArchiveSummary {

    @TableId
    private Long id;
    private Long taskId;
    private Long cleanupBatchId;
    private String organization;
    private String repository;
    private Integer prNumber;
    private String title;
    private String commitSha;
    private String branchName;
    private String status;
    private String riskLevel;
    private String assessmentStatus;
    private String source;
    private String triggerSource;
    private LocalDateTime createdAt;
    private LocalDateTime finishedAt;
    private Integer durationSeconds;
    private Integer findingCount;
    private Integer missingTestCount;
    private Integer changedFileCount;
    private Integer timelineCount;
    private Integer publicationCount;
    private String backupReference;
    private LocalDateTime archivedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTaskId() {
        return taskId;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }

    public Long getCleanupBatchId() {
        return cleanupBatchId;
    }

    public void setCleanupBatchId(Long cleanupBatchId) {
        this.cleanupBatchId = cleanupBatchId;
    }

    public String getOrganization() {
        return organization;
    }

    public void setOrganization(String organization) {
        this.organization = organization;
    }

    public String getRepository() {
        return repository;
    }

    public void setRepository(String repository) {
        this.repository = repository;
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

    public String getAssessmentStatus() {
        return assessmentStatus;
    }

    public void setAssessmentStatus(String assessmentStatus) {
        this.assessmentStatus = assessmentStatus;
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

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
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

    public Integer getFindingCount() {
        return findingCount;
    }

    public void setFindingCount(Integer findingCount) {
        this.findingCount = findingCount;
    }

    public Integer getMissingTestCount() {
        return missingTestCount;
    }

    public void setMissingTestCount(Integer missingTestCount) {
        this.missingTestCount = missingTestCount;
    }

    public Integer getChangedFileCount() {
        return changedFileCount;
    }

    public void setChangedFileCount(Integer changedFileCount) {
        this.changedFileCount = changedFileCount;
    }

    public Integer getTimelineCount() {
        return timelineCount;
    }

    public void setTimelineCount(Integer timelineCount) {
        this.timelineCount = timelineCount;
    }

    public Integer getPublicationCount() {
        return publicationCount;
    }

    public void setPublicationCount(Integer publicationCount) {
        this.publicationCount = publicationCount;
    }

    public String getBackupReference() {
        return backupReference;
    }

    public void setBackupReference(String backupReference) {
        this.backupReference = backupReference;
    }

    public LocalDateTime getArchivedAt() {
        return archivedAt;
    }

    public void setArchivedAt(LocalDateTime archivedAt) {
        this.archivedAt = archivedAt;
    }
}
