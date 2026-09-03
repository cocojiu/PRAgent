package com.repoguard.agent.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("review_finding")
public class ReviewFinding {

    @TableId
    private Long id;
    private Long taskId;
    private Long attemptId;
    private String findingFingerprint;
    private Long previousFindingId;
    private String comparisonStatus;
    private BigDecimal comparisonConfidence;
    private String comparisonReason;
    private String comparisonVersion;
    private Long comparisonAttemptId;
    private Long sourceBatchId;
    private Boolean currentAttempt;
    private String category;
    private String severity;
    private String source;
    private String ruleId;
    private String filePath;
    private Integer lineNumber;
    private String message;
    private String recommendation;
    private String confidence;
    private String evidence;
    private String impact;
    private String fixExample;
    private Boolean isBlocking;
    private String enforcementMode;
    private String policyReason;
    private String issueType;
    private String preconditions;
    private String relatedFiles;
    private Boolean blockingCandidate;
    private String verificationStatus;
    private String detectorVersion;
    private Long ruleConfigVersion;
    private String promptVersion;
    private String contextVersion;
    private String schemaVersion;
    private String verifierVersion;
    private String aggregationVersion;
    private Long policyVersion;
    private String llmProvider;
    private String llmModel;
    private String originalSeverity;
    private String originalConfidence;
    private Boolean originalIsBlocking;
    private String downgradeReason;
    private String blockReason;
    private String anchorType;
    private String reviewDimension;
    private String methodName;
    private String testType;
    private String feedbackStatus;
    private String feedbackNote;
    private String feedbackBy;
    private LocalDateTime feedbackAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public Long getAttemptId() { return attemptId; }
    public void setAttemptId(Long attemptId) { this.attemptId = attemptId; }
    public String getFindingFingerprint() { return findingFingerprint; }
    public void setFindingFingerprint(String findingFingerprint) { this.findingFingerprint = findingFingerprint; }
    public Long getPreviousFindingId() { return previousFindingId; }
    public void setPreviousFindingId(Long previousFindingId) { this.previousFindingId = previousFindingId; }
    public String getComparisonStatus() { return comparisonStatus; }
    public void setComparisonStatus(String comparisonStatus) { this.comparisonStatus = comparisonStatus; }
    public BigDecimal getComparisonConfidence() { return comparisonConfidence; }
    public void setComparisonConfidence(BigDecimal comparisonConfidence) { this.comparisonConfidence = comparisonConfidence; }
    public String getComparisonReason() { return comparisonReason; }
    public void setComparisonReason(String comparisonReason) { this.comparisonReason = comparisonReason; }
    public String getComparisonVersion() { return comparisonVersion; }
    public void setComparisonVersion(String comparisonVersion) { this.comparisonVersion = comparisonVersion; }
    public Long getComparisonAttemptId() { return comparisonAttemptId; }
    public void setComparisonAttemptId(Long comparisonAttemptId) { this.comparisonAttemptId = comparisonAttemptId; }
    public Long getSourceBatchId() { return sourceBatchId; }
    public void setSourceBatchId(Long sourceBatchId) { this.sourceBatchId = sourceBatchId; }
    public Boolean getCurrentAttempt() { return currentAttempt; }
    public void setCurrentAttempt(Boolean currentAttempt) { this.currentAttempt = currentAttempt; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getRuleId() { return ruleId; }
    public void setRuleId(String ruleId) { this.ruleId = ruleId; }
    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }
    public Integer getLineNumber() { return lineNumber; }
    public void setLineNumber(Integer lineNumber) { this.lineNumber = lineNumber; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getRecommendation() { return recommendation; }
    public void setRecommendation(String recommendation) { this.recommendation = recommendation; }
    public String getConfidence() { return confidence; }
    public void setConfidence(String confidence) { this.confidence = confidence; }
    public String getEvidence() { return evidence; }
    public void setEvidence(String evidence) { this.evidence = evidence; }
    public String getImpact() { return impact; }
    public void setImpact(String impact) { this.impact = impact; }
    public String getFixExample() { return fixExample; }
    public void setFixExample(String fixExample) { this.fixExample = fixExample; }
    public Boolean getIsBlocking() { return isBlocking; }
    public void setIsBlocking(Boolean isBlocking) { this.isBlocking = isBlocking; }
    public String getEnforcementMode() { return enforcementMode; }
    public void setEnforcementMode(String enforcementMode) { this.enforcementMode = enforcementMode; }
    public String getPolicyReason() { return policyReason; }
    public void setPolicyReason(String policyReason) { this.policyReason = policyReason; }
    public String getIssueType() { return issueType; }
    public void setIssueType(String issueType) { this.issueType = issueType; }
    public String getPreconditions() { return preconditions; }
    public void setPreconditions(String preconditions) { this.preconditions = preconditions; }
    public String getRelatedFiles() { return relatedFiles; }
    public void setRelatedFiles(String relatedFiles) { this.relatedFiles = relatedFiles; }
    public Boolean getBlockingCandidate() { return blockingCandidate; }
    public void setBlockingCandidate(Boolean blockingCandidate) { this.blockingCandidate = blockingCandidate; }
    public String getVerificationStatus() { return verificationStatus; }
    public void setVerificationStatus(String verificationStatus) { this.verificationStatus = verificationStatus; }
    public String getDetectorVersion() { return detectorVersion; }
    public void setDetectorVersion(String detectorVersion) { this.detectorVersion = detectorVersion; }
    public Long getRuleConfigVersion() { return ruleConfigVersion; }
    public void setRuleConfigVersion(Long ruleConfigVersion) { this.ruleConfigVersion = ruleConfigVersion; }
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
    public Long getPolicyVersion() { return policyVersion; }
    public void setPolicyVersion(Long policyVersion) { this.policyVersion = policyVersion; }
    public String getLlmProvider() { return llmProvider; }
    public void setLlmProvider(String llmProvider) { this.llmProvider = llmProvider; }
    public String getLlmModel() { return llmModel; }
    public void setLlmModel(String llmModel) { this.llmModel = llmModel; }
    public String getOriginalSeverity() { return originalSeverity; }
    public void setOriginalSeverity(String originalSeverity) { this.originalSeverity = originalSeverity; }
    public String getOriginalConfidence() { return originalConfidence; }
    public void setOriginalConfidence(String originalConfidence) { this.originalConfidence = originalConfidence; }
    public Boolean getOriginalIsBlocking() { return originalIsBlocking; }
    public void setOriginalIsBlocking(Boolean originalIsBlocking) { this.originalIsBlocking = originalIsBlocking; }
    public String getDowngradeReason() { return downgradeReason; }
    public void setDowngradeReason(String downgradeReason) { this.downgradeReason = downgradeReason; }
    public String getBlockReason() { return blockReason; }
    public void setBlockReason(String blockReason) { this.blockReason = blockReason; }
    public String getAnchorType() { return anchorType; }
    public void setAnchorType(String anchorType) { this.anchorType = anchorType; }
    public String getReviewDimension() { return reviewDimension; }
    public void setReviewDimension(String reviewDimension) { this.reviewDimension = reviewDimension; }
    public String getMethodName() { return methodName; }
    public void setMethodName(String methodName) { this.methodName = methodName; }
    public String getTestType() { return testType; }
    public void setTestType(String testType) { this.testType = testType; }
    public String getFeedbackStatus() { return feedbackStatus; }
    public void setFeedbackStatus(String feedbackStatus) { this.feedbackStatus = feedbackStatus; }
    public String getFeedbackNote() { return feedbackNote; }
    public void setFeedbackNote(String feedbackNote) { this.feedbackNote = feedbackNote; }
    public String getFeedbackBy() { return feedbackBy; }
    public void setFeedbackBy(String feedbackBy) { this.feedbackBy = feedbackBy; }
    public LocalDateTime getFeedbackAt() { return feedbackAt; }
    public void setFeedbackAt(LocalDateTime feedbackAt) { this.feedbackAt = feedbackAt; }
}
