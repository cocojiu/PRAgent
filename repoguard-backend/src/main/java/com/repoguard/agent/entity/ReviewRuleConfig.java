package com.repoguard.agent.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("review_rule_config")
public class ReviewRuleConfig {

    @TableId
    private String id;
    private String detectorVersion;
    private Long configVersion;
    private Long policyVersion;
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
    private Integer sortOrder;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getDetectorVersion() {
        return detectorVersion;
    }

    public void setDetectorVersion(String detectorVersion) {
        this.detectorVersion = detectorVersion;
    }

    public Long getConfigVersion() {
        return configVersion;
    }

    public void setConfigVersion(Long configVersion) {
        this.configVersion = configVersion;
    }

    public Long getPolicyVersion() {
        return policyVersion;
    }

    public void setPolicyVersion(Long policyVersion) {
        this.policyVersion = policyVersion;
    }

    public String getRuleName() {
        return ruleName;
    }

    public void setRuleName(String ruleName) {
        this.ruleName = ruleName;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public String getApplicableLanguages() {
        return applicableLanguages;
    }

    public void setApplicableLanguages(String applicableLanguages) {
        this.applicableLanguages = applicableLanguages;
    }

    public String getFilePatterns() {
        return filePatterns;
    }

    public void setFilePatterns(String filePatterns) {
        this.filePatterns = filePatterns;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getConfidence() {
        return confidence;
    }

    public void setConfidence(Integer confidence) {
        this.confidence = confidence;
    }

    public String getEnforcementMode() {
        return enforcementMode;
    }

    public void setEnforcementMode(String enforcementMode) {
        this.enforcementMode = enforcementMode;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getPositiveExample() {
        return positiveExample;
    }

    public void setPositiveExample(String positiveExample) {
        this.positiveExample = positiveExample;
    }

    public String getFalsePositiveGuidance() {
        return falsePositiveGuidance;
    }

    public void setFalsePositiveGuidance(String falsePositiveGuidance) {
        this.falsePositiveGuidance = falsePositiveGuidance;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
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
