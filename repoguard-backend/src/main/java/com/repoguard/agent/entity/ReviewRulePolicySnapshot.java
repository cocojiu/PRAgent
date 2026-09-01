package com.repoguard.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("review_rule_policy_snapshot")
public class ReviewRulePolicySnapshot {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String ruleId;
    private Long policyVersion;
    private Long configVersion;
    private String detectorVersion;
    private String detectorType;
    private String matcherExpression;
    private String exceptionPatterns;
    private String ruleName;
    private String scope;
    private String applicableLanguages;
    private String filePatterns;
    private String severity;
    private String status;
    private Integer confidence;
    private String enforcementMode;
    private String description;
    private String positiveExample;
    private String falsePositiveGuidance;
    private String changeType;
    private Long sourcePolicyVersion;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getRuleId() { return ruleId; }
    public void setRuleId(String ruleId) { this.ruleId = ruleId; }
    public Long getPolicyVersion() { return policyVersion; }
    public void setPolicyVersion(Long policyVersion) { this.policyVersion = policyVersion; }
    public Long getConfigVersion() { return configVersion; }
    public void setConfigVersion(Long configVersion) { this.configVersion = configVersion; }
    public String getDetectorVersion() { return detectorVersion; }
    public void setDetectorVersion(String detectorVersion) { this.detectorVersion = detectorVersion; }
    public String getDetectorType() { return detectorType; }
    public void setDetectorType(String detectorType) { this.detectorType = detectorType; }
    public String getMatcherExpression() { return matcherExpression; }
    public void setMatcherExpression(String matcherExpression) { this.matcherExpression = matcherExpression; }
    public String getExceptionPatterns() { return exceptionPatterns; }
    public void setExceptionPatterns(String exceptionPatterns) { this.exceptionPatterns = exceptionPatterns; }
    public String getRuleName() { return ruleName; }
    public void setRuleName(String ruleName) { this.ruleName = ruleName; }
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public String getApplicableLanguages() { return applicableLanguages; }
    public void setApplicableLanguages(String applicableLanguages) { this.applicableLanguages = applicableLanguages; }
    public String getFilePatterns() { return filePatterns; }
    public void setFilePatterns(String filePatterns) { this.filePatterns = filePatterns; }
    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getConfidence() { return confidence; }
    public void setConfidence(Integer confidence) { this.confidence = confidence; }
    public String getEnforcementMode() { return enforcementMode; }
    public void setEnforcementMode(String enforcementMode) { this.enforcementMode = enforcementMode; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getPositiveExample() { return positiveExample; }
    public void setPositiveExample(String positiveExample) { this.positiveExample = positiveExample; }
    public String getFalsePositiveGuidance() { return falsePositiveGuidance; }
    public void setFalsePositiveGuidance(String falsePositiveGuidance) { this.falsePositiveGuidance = falsePositiveGuidance; }
    public String getChangeType() { return changeType; }
    public void setChangeType(String changeType) { this.changeType = changeType; }
    public Long getSourcePolicyVersion() { return sourcePolicyVersion; }
    public void setSourcePolicyVersion(Long sourcePolicyVersion) { this.sourcePolicyVersion = sourcePolicyVersion; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
