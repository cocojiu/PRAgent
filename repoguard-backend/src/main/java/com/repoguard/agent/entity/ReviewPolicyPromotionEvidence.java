package com.repoguard.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("review_policy_promotion_evidence")
public class ReviewPolicyPromotionEvidence {

    private Long id;
    private String targetType;
    private Long rulePolicySnapshotId;
    private Long strategyPolicySnapshotId;
    private String ruleId;
    private String sourceEnforcementMode;
    private String targetEnforcementMode;
    private String qualityBaselineVersion;
    private String qualityGateVersion;
    private LocalDateTime baselineCalculatedAt;
    private LocalDateTime sampleCutoffAt;
    private Long totalSamples;
    private Long labeledSamples;
    private Long totalHighRiskSamples;
    private Long labeledHighRiskSamples;
    private Long confirmedValidSamples;
    private Long falsePositiveSamples;
    private Long anchoredSamples;
    private Long duplicateSamples;
    private BigDecimal precision;
    private BigDecimal precisionWilsonLowerBound;
    private BigDecimal falsePositiveRate;
    private BigDecimal anchorRate;
    private BigDecimal duplicateRate;
    private Boolean commentEligible;
    private Boolean blockEligible;
    private String qualityStatus;
    private String blockers;
    private String sampleFingerprint;
    private Long actorUserId;
    private String actorUsername;
    private String traceId;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTargetType() { return targetType; }
    public void setTargetType(String targetType) { this.targetType = targetType; }
    public Long getRulePolicySnapshotId() { return rulePolicySnapshotId; }
    public void setRulePolicySnapshotId(Long rulePolicySnapshotId) { this.rulePolicySnapshotId = rulePolicySnapshotId; }
    public Long getStrategyPolicySnapshotId() { return strategyPolicySnapshotId; }
    public void setStrategyPolicySnapshotId(Long strategyPolicySnapshotId) { this.strategyPolicySnapshotId = strategyPolicySnapshotId; }
    public String getRuleId() { return ruleId; }
    public void setRuleId(String ruleId) { this.ruleId = ruleId; }
    public String getSourceEnforcementMode() { return sourceEnforcementMode; }
    public void setSourceEnforcementMode(String sourceEnforcementMode) { this.sourceEnforcementMode = sourceEnforcementMode; }
    public String getTargetEnforcementMode() { return targetEnforcementMode; }
    public void setTargetEnforcementMode(String targetEnforcementMode) { this.targetEnforcementMode = targetEnforcementMode; }
    public String getQualityBaselineVersion() { return qualityBaselineVersion; }
    public void setQualityBaselineVersion(String qualityBaselineVersion) { this.qualityBaselineVersion = qualityBaselineVersion; }
    public String getQualityGateVersion() { return qualityGateVersion; }
    public void setQualityGateVersion(String qualityGateVersion) { this.qualityGateVersion = qualityGateVersion; }
    public LocalDateTime getBaselineCalculatedAt() { return baselineCalculatedAt; }
    public void setBaselineCalculatedAt(LocalDateTime baselineCalculatedAt) { this.baselineCalculatedAt = baselineCalculatedAt; }
    public LocalDateTime getSampleCutoffAt() { return sampleCutoffAt; }
    public void setSampleCutoffAt(LocalDateTime sampleCutoffAt) { this.sampleCutoffAt = sampleCutoffAt; }
    public Long getTotalSamples() { return totalSamples; }
    public void setTotalSamples(Long totalSamples) { this.totalSamples = totalSamples; }
    public Long getLabeledSamples() { return labeledSamples; }
    public void setLabeledSamples(Long labeledSamples) { this.labeledSamples = labeledSamples; }
    public Long getTotalHighRiskSamples() { return totalHighRiskSamples; }
    public void setTotalHighRiskSamples(Long totalHighRiskSamples) { this.totalHighRiskSamples = totalHighRiskSamples; }
    public Long getLabeledHighRiskSamples() { return labeledHighRiskSamples; }
    public void setLabeledHighRiskSamples(Long labeledHighRiskSamples) { this.labeledHighRiskSamples = labeledHighRiskSamples; }
    public Long getConfirmedValidSamples() { return confirmedValidSamples; }
    public void setConfirmedValidSamples(Long confirmedValidSamples) { this.confirmedValidSamples = confirmedValidSamples; }
    public Long getFalsePositiveSamples() { return falsePositiveSamples; }
    public void setFalsePositiveSamples(Long falsePositiveSamples) { this.falsePositiveSamples = falsePositiveSamples; }
    public Long getAnchoredSamples() { return anchoredSamples; }
    public void setAnchoredSamples(Long anchoredSamples) { this.anchoredSamples = anchoredSamples; }
    public Long getDuplicateSamples() { return duplicateSamples; }
    public void setDuplicateSamples(Long duplicateSamples) { this.duplicateSamples = duplicateSamples; }
    public BigDecimal getPrecision() { return precision; }
    public void setPrecision(BigDecimal precision) { this.precision = precision; }
    public BigDecimal getPrecisionWilsonLowerBound() { return precisionWilsonLowerBound; }
    public void setPrecisionWilsonLowerBound(BigDecimal precisionWilsonLowerBound) { this.precisionWilsonLowerBound = precisionWilsonLowerBound; }
    public BigDecimal getFalsePositiveRate() { return falsePositiveRate; }
    public void setFalsePositiveRate(BigDecimal falsePositiveRate) { this.falsePositiveRate = falsePositiveRate; }
    public BigDecimal getAnchorRate() { return anchorRate; }
    public void setAnchorRate(BigDecimal anchorRate) { this.anchorRate = anchorRate; }
    public BigDecimal getDuplicateRate() { return duplicateRate; }
    public void setDuplicateRate(BigDecimal duplicateRate) { this.duplicateRate = duplicateRate; }
    public Boolean getCommentEligible() { return commentEligible; }
    public void setCommentEligible(Boolean commentEligible) { this.commentEligible = commentEligible; }
    public Boolean getBlockEligible() { return blockEligible; }
    public void setBlockEligible(Boolean blockEligible) { this.blockEligible = blockEligible; }
    public String getQualityStatus() { return qualityStatus; }
    public void setQualityStatus(String qualityStatus) { this.qualityStatus = qualityStatus; }
    public String getBlockers() { return blockers; }
    public void setBlockers(String blockers) { this.blockers = blockers; }
    public String getSampleFingerprint() { return sampleFingerprint; }
    public void setSampleFingerprint(String sampleFingerprint) { this.sampleFingerprint = sampleFingerprint; }
    public Long getActorUserId() { return actorUserId; }
    public void setActorUserId(Long actorUserId) { this.actorUserId = actorUserId; }
    public String getActorUsername() { return actorUsername; }
    public void setActorUsername(String actorUsername) { this.actorUsername = actorUsername; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
