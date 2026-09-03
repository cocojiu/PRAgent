package com.repoguard.agent.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("github_check_run")
public class GithubCheckRun {

    @TableId
    private Long id;
    private Long taskId;
    private Integer runSequence;
    private Long githubCheckRunId;
    private String name;
    private String headSha;
    private String externalId;
    private String desiredStage;
    private String appliedStage;
    private Long desiredVersion;
    private Long appliedVersion;
    private Integer dispatchAttempts;
    private LocalDateTime nextDispatchAt;
    private LocalDateTime claimedAt;
    private String claimedBy;
    private String lastError;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public Integer getRunSequence() { return runSequence; }
    public void setRunSequence(Integer runSequence) { this.runSequence = runSequence; }
    public Long getGithubCheckRunId() { return githubCheckRunId; }
    public void setGithubCheckRunId(Long githubCheckRunId) { this.githubCheckRunId = githubCheckRunId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getHeadSha() { return headSha; }
    public void setHeadSha(String headSha) { this.headSha = headSha; }
    public String getExternalId() { return externalId; }
    public void setExternalId(String externalId) { this.externalId = externalId; }
    public String getDesiredStage() { return desiredStage; }
    public void setDesiredStage(String desiredStage) { this.desiredStage = desiredStage; }
    public String getAppliedStage() { return appliedStage; }
    public void setAppliedStage(String appliedStage) { this.appliedStage = appliedStage; }
    public Long getDesiredVersion() { return desiredVersion; }
    public void setDesiredVersion(Long desiredVersion) { this.desiredVersion = desiredVersion; }
    public Long getAppliedVersion() { return appliedVersion; }
    public void setAppliedVersion(Long appliedVersion) { this.appliedVersion = appliedVersion; }
    public Integer getDispatchAttempts() { return dispatchAttempts; }
    public void setDispatchAttempts(Integer dispatchAttempts) { this.dispatchAttempts = dispatchAttempts; }
    public LocalDateTime getNextDispatchAt() { return nextDispatchAt; }
    public void setNextDispatchAt(LocalDateTime nextDispatchAt) { this.nextDispatchAt = nextDispatchAt; }
    public LocalDateTime getClaimedAt() { return claimedAt; }
    public void setClaimedAt(LocalDateTime claimedAt) { this.claimedAt = claimedAt; }
    public String getClaimedBy() { return claimedBy; }
    public void setClaimedBy(String claimedBy) { this.claimedBy = claimedBy; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
