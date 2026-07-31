package com.repoguard.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("review_strategy_policy_snapshot")
public class ReviewStrategyPolicySnapshot {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long strategyVersion;
    private String promptVersion;
    private String contextVersion;
    private String schemaVersion;
    private String verifierVersion;
    private String aggregationVersion;
    private String enforcementMode;
    private Boolean replayVerified;
    private Boolean active;
    private String changeType;
    private Long sourceSnapshotId;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getStrategyVersion() { return strategyVersion; }
    public void setStrategyVersion(Long strategyVersion) { this.strategyVersion = strategyVersion; }
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
    public String getEnforcementMode() { return enforcementMode; }
    public void setEnforcementMode(String enforcementMode) { this.enforcementMode = enforcementMode; }
    public Boolean getReplayVerified() { return replayVerified; }
    public void setReplayVerified(Boolean replayVerified) { this.replayVerified = replayVerified; }
    public Boolean getActive() { return active; }
    public void setActive(Boolean active) { this.active = active; }
    public String getChangeType() { return changeType; }
    public void setChangeType(String changeType) { this.changeType = changeType; }
    public Long getSourceSnapshotId() { return sourceSnapshotId; }
    public void setSourceSnapshotId(Long sourceSnapshotId) { this.sourceSnapshotId = sourceSnapshotId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
