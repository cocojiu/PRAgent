package com.repoguard.agent.worker;

import com.repoguard.agent.entity.ReviewFinding;
import com.repoguard.agent.review.ReviewFindingResult;
import com.repoguard.agent.review.ReviewExecutionProvenance;
import com.repoguard.agent.review.ReviewResult;
import java.util.Locale;
import org.springframework.util.StringUtils;
import org.springframework.stereotype.Component;

@Component
class ReviewFindingEntityMapper {

    ReviewFinding toEntity(Long taskId, ReviewFindingResult findingResult) {
        return toEntity(taskId, findingResult, ReviewResult.completed(findingResult.severity(), java.util.List.of(findingResult)));
    }

    ReviewFinding toEntity(Long taskId, ReviewFindingResult findingResult, ReviewResult reviewResult) {
        ReviewFinding finding = new ReviewFinding();
        boolean llmFinding = sourceContains(findingResult.source(), "LLM");
        ReviewExecutionProvenance execution = reviewResult.executionProvenance();
        finding.setTaskId(taskId);
        finding.setCategory("FINDING");
        finding.setSeverity(findingResult.severity());
        finding.setSource(findingResult.source());
        finding.setRuleId(findingResult.ruleId());
        finding.setFilePath(findingResult.filePath());
        finding.setLineNumber(findingResult.lineNumber());
        finding.setMessage(findingResult.message());
        finding.setRecommendation(findingResult.recommendation());
        finding.setConfidence(findingResult.confidence());
        finding.setEvidence(findingResult.evidence());
        finding.setImpact(findingResult.impact());
        finding.setFixExample(findingResult.fixExample());
        finding.setIsBlocking(findingResult.isBlocking());
        finding.setEnforcementMode(findingResult.enforcementMode());
        finding.setPolicyReason(findingResult.policyReason());
        finding.setIssueType(findingResult.issueType());
        finding.setPreconditions(findingResult.preconditions());
        finding.setRelatedFiles(String.join("\n", findingResult.relatedFiles()));
        finding.setBlockingCandidate(findingResult.blockingCandidate());
        finding.setVerificationStatus(findingResult.verificationStatus());
        finding.setDetectorVersion(findingResult.provenance().detectorVersion());
        finding.setRuleConfigVersion(findingResult.provenance().ruleConfigVersion());
        finding.setPromptVersion(llmFinding ? execution.promptVersion() : "not-applicable");
        finding.setContextVersion(llmFinding ? execution.contextVersion() : "not-applicable");
        finding.setSchemaVersion(llmFinding ? execution.schemaVersion() : "not-applicable");
        finding.setVerifierVersion(llmFinding ? execution.verifierVersion() : "not-applicable");
        finding.setAggregationVersion(execution.aggregationVersion());
        finding.setPolicyVersion(llmFinding
            ? execution.strategyPolicyVersion()
            : findingResult.provenance().rulePolicyVersion());
        finding.setLlmProvider(llmFinding ? reviewResult.llmProvider() : null);
        finding.setLlmModel(llmFinding ? reviewResult.llmModel() : null);
        finding.setOriginalSeverity(findingResult.provenance().originalSeverity());
        finding.setOriginalConfidence(findingResult.provenance().originalConfidence());
        finding.setOriginalIsBlocking(findingResult.blockingCandidate() || findingResult.isBlocking());
        finding.setDowngradeReason(downgradeReason(findingResult));
        finding.setBlockReason(findingResult.isBlocking() ? safeReason(findingResult.policyReason()) : "");
        finding.setAnchorType(anchorType(findingResult));
        finding.setReviewDimension(findingResult.reviewDimension());
        return finding;
    }

    private String anchorType(ReviewFindingResult finding) {
        if (finding.lineNumber() != null && finding.lineNumber() > 0) {
            return "ADDED_LINE";
        }
        return finding.relatedFiles().isEmpty() ? "NONE" : "CROSS_FILE";
    }

    private String downgradeReason(ReviewFindingResult finding) {
        boolean severityChanged = !equalsIgnoreCase(finding.provenance().originalSeverity(), finding.severity());
        boolean confidenceChanged = !equalsIgnoreCase(finding.provenance().originalConfidence(), finding.confidence());
        if (!severityChanged && !confidenceChanged) {
            return "";
        }
        return safeReason(finding.policyReason());
    }

    private boolean equalsIgnoreCase(String first, String second) {
        return first == null ? second == null : first.equalsIgnoreCase(second);
    }

    private boolean sourceContains(String value, String source) {
        return value != null && value.toUpperCase(Locale.ROOT).contains(source);
    }

    private String safeReason(String value) {
        if (!StringUtils.hasText(value)) {
            return "unspecified";
        }
        String normalized = value.trim().replaceAll("\\s+", " ");
        return normalized.length() <= 512 ? normalized : normalized.substring(0, 512);
    }
}
