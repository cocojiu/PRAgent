package com.repoguard.agent.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record LlmModelReleaseDto(Long id, String releaseKey, String provider, String modelName, String promptVersion,
    String contextVersion, String schemaVersion, String datasetId, String datasetVersion, String datasetFingerprint,
    String state, Integer trafficPercent, Boolean qualityGatePassed, BigDecimal precisionRate, BigDecimal recallRate,
    BigDecimal anchorRate, BigDecimal duplicateRate, BigDecimal parseFailureRate, Long p95LatencyMs,
    BigDecimal averageCost, Long totalTokens, List<String> blockers, String rollbackReason, String createdBy,
    LocalDateTime createdAt, LocalDateTime updatedAt, Long evaluationReportId) {

    public LlmModelReleaseDto(Long id, String releaseKey, String provider, String modelName, String promptVersion,
        String contextVersion, String schemaVersion, String datasetId, String datasetVersion, String datasetFingerprint,
        String state, Integer trafficPercent, Boolean qualityGatePassed, BigDecimal precisionRate, BigDecimal recallRate,
        BigDecimal anchorRate, BigDecimal duplicateRate, BigDecimal parseFailureRate, Long p95LatencyMs,
        BigDecimal averageCost, Long totalTokens, List<String> blockers, String rollbackReason, String createdBy,
        LocalDateTime createdAt, LocalDateTime updatedAt) {
        this(id, releaseKey, provider, modelName, promptVersion, contextVersion, schemaVersion, datasetId, datasetVersion,
            datasetFingerprint, state, trafficPercent, qualityGatePassed, precisionRate, recallRate, anchorRate,
            duplicateRate, parseFailureRate, p95LatencyMs, averageCost, totalTokens, blockers, rollbackReason, createdBy,
            createdAt, updatedAt, null);
    }

    /** Public, aggregate-only view of an immutable evaluation report. */
    public record EvaluationReportDto(Long id, String reportKey, String status, String datasetId, String datasetVersion,
        String datasetKind, Integer sourceRepositoryCount, Integer sampleCount, Integer fixedRegressionSamples,
        Integer rollingObservationSamples, Boolean authorized, Boolean anonymized, Boolean humanReviewed,
        String sampleFingerprint, String provider, String model, String promptVersion, String contextVersion,
        String schemaVersion, String chunkPolicyVersion, BigDecimal temperature, String ruleVersion, String codeRevision,
        Integer expectedFindings, Integer predictedFindings, Integer truePositives, Integer falsePositives,
        Integer falseNegatives, BigDecimal precision, BigDecimal recall, BigDecimal precisionWilsonLowerBound,
        BigDecimal anchorRate, BigDecimal duplicateRate, BigDecimal parseFailureRate,
        Map<String, Map<String, Long>> severityConfusion, Long totalLatencyMs, Long totalTokens, BigDecimal totalCost,
        List<String> blockers, Boolean eligible, EvaluationMetricsDto metrics, String createdBy, LocalDateTime createdAt) {
    }

    public record EvaluationMetricsDto(Integer labeledComments, Integer usefulComments, Integer falsePositiveComments,
        Integer publishAttempts, Integer publishedComments, Integer fixedComments, Integer ignoredComments,
        BigDecimal usefulCommentRate, BigDecimal falsePositiveCommentRate, BigDecimal publishSuccessRate,
        BigDecimal fixRate, BigDecimal ignoredRate, Long p50LatencyMs, Long p95LatencyMs, BigDecimal averageLatencyMs,
        BigDecimal averageTokensPerSample, BigDecimal averageCostPerSample, Long ruleFindings, Long llmFindings,
        Long verifiedFindings, BigDecimal ruleContributionRate, BigDecimal llmContributionRate,
        BigDecimal verifiedContributionRate) {
    }

    public record EvaluationReportComparisonDto(Long baselineReportId, Long candidateReportId, BigDecimal precisionDelta,
        BigDecimal recallDelta, BigDecimal anchorRateDelta, BigDecimal duplicateRateDelta,
        BigDecimal parseFailureRateDelta, Long p95LatencyDeltaMs, BigDecimal costDelta, Boolean candidateImproved,
        List<String> blockers) {
    }

    public record EvaluationExportDto(Long reportId, String format, String contentSha256, String content) {
    }

    /** Tenant-scoped, aggregate-only evidence for a release state transition. */
    public record LlmModelReleaseAuditDto(Long id, Long releaseId, String releaseKey, String action,
        String fromState, String toState, Integer trafficPercent, String operator, String reason,
        String detailsJson, String eventHash, LocalDateTime createdAt, Boolean hashValid, String hashStatus) {
    }

    /** Bounded audit export payload; it deliberately excludes source/provider secrets. */
    public record LlmModelReleaseAuditExportDto(String format, Long recordCount, String contentSha256, String content) {
    }

    /** Result of verifying one append-only audit event hash. */
    public record LlmModelReleaseAuditVerificationDto(Long auditId, Long releaseId, String releaseKey,
        String storedHash, String calculatedHash, Boolean valid, String status) {
    }
}
