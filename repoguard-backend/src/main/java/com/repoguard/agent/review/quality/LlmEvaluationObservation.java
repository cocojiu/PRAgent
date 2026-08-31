package com.repoguard.agent.review.quality;

import java.math.BigDecimal;

/**
 * A single manually labelled live-evaluation observation. The case id is the stable identity used
 * for the sample fingerprint; no prompt or source content is retained in the report.
 */
public record LlmEvaluationObservation(
    String caseId,
    String category,
    boolean expectedFinding,
    String expectedSeverity,
    boolean predictedFinding,
    String predictedSeverity,
    boolean anchorValid,
    String predictionKey,
    boolean parseSucceeded,
    long latencyMs,
    long totalTokens,
    BigDecimal estimatedCost,
    Boolean usefulComment,
    boolean commentPublishAttempted,
    Boolean commentPublished,
    Boolean commentFixed,
    Boolean commentIgnored,
    long ruleFindingCount,
    long llmFindingCount,
    long verifiedFindingCount,
    EvaluationSplit split,
    String sourceRepositoryKey
) {

    public enum EvaluationSplit {
        FIXED_REGRESSION,
        ROLLING_OBSERVATION,
        UNSPECIFIED
    }

    public LlmEvaluationObservation(
        String caseId,
        String category,
        boolean expectedFinding,
        String expectedSeverity,
        boolean predictedFinding,
        String predictedSeverity,
        boolean anchorValid,
        String predictionKey,
        boolean parseSucceeded,
        long latencyMs,
        long totalTokens,
        BigDecimal estimatedCost
    ) {
        this(
            caseId,
            category,
            expectedFinding,
            expectedSeverity,
            predictedFinding,
            predictedSeverity,
            anchorValid,
            predictionKey,
            parseSucceeded,
            latencyMs,
            totalTokens,
            estimatedCost,
            null,
            false,
            null,
            null,
            null,
            0,
            0,
            0,
            EvaluationSplit.UNSPECIFIED,
            ""
        );
    }

    public LlmEvaluationObservation(
        String caseId,
        String category,
        boolean expectedFinding,
        String expectedSeverity,
        boolean predictedFinding,
        String predictedSeverity,
        boolean anchorValid,
        String predictionKey,
        boolean parseSucceeded,
        long latencyMs,
        long totalTokens,
        BigDecimal estimatedCost,
        Boolean usefulComment,
        boolean commentPublishAttempted,
        Boolean commentPublished,
        Boolean commentFixed,
        Boolean commentIgnored,
        long ruleFindingCount,
        long llmFindingCount,
        long verifiedFindingCount
    ) {
        this(
            caseId,
            category,
            expectedFinding,
            expectedSeverity,
            predictedFinding,
            predictedSeverity,
            anchorValid,
            predictionKey,
            parseSucceeded,
            latencyMs,
            totalTokens,
            estimatedCost,
            usefulComment,
            commentPublishAttempted,
            commentPublished,
            commentFixed,
            commentIgnored,
            ruleFindingCount,
            llmFindingCount,
            verifiedFindingCount,
            EvaluationSplit.UNSPECIFIED,
            ""
        );
    }

    public LlmEvaluationObservation(
        String caseId,
        String category,
        boolean expectedFinding,
        String expectedSeverity,
        boolean predictedFinding,
        String predictedSeverity,
        boolean anchorValid,
        String predictionKey,
        boolean parseSucceeded,
        long latencyMs,
        long totalTokens,
        BigDecimal estimatedCost,
        Boolean usefulComment,
        boolean commentPublishAttempted,
        Boolean commentPublished,
        Boolean commentFixed,
        Boolean commentIgnored,
        long ruleFindingCount,
        long llmFindingCount,
        long verifiedFindingCount,
        EvaluationSplit split
    ) {
        this(
            caseId,
            category,
            expectedFinding,
            expectedSeverity,
            predictedFinding,
            predictedSeverity,
            anchorValid,
            predictionKey,
            parseSucceeded,
            latencyMs,
            totalTokens,
            estimatedCost,
            usefulComment,
            commentPublishAttempted,
            commentPublished,
            commentFixed,
            commentIgnored,
            ruleFindingCount,
            llmFindingCount,
            verifiedFindingCount,
            split,
            ""
        );
    }

    public LlmEvaluationObservation {
        caseId = normalize(caseId, "caseId");
        category = normalize(category, "category");
        expectedSeverity = normalizeSeverity(expectedSeverity);
        predictedSeverity = normalizeSeverity(predictedSeverity);
        predictionKey = predictionKey == null ? "" : predictionKey.trim();
        latencyMs = Math.max(0, latencyMs);
        totalTokens = Math.max(0, totalTokens);
        estimatedCost = estimatedCost == null ? BigDecimal.ZERO : estimatedCost.max(BigDecimal.ZERO);
        commentPublishAttempted = commentPublishAttempted || commentPublished != null;
        split = split == null ? EvaluationSplit.UNSPECIFIED : split;
        sourceRepositoryKey = normalizeRepositoryKey(sourceRepositoryKey);
        if (Boolean.TRUE.equals(commentFixed) && Boolean.TRUE.equals(commentIgnored)) {
            throw new IllegalArgumentException("A comment cannot be both fixed and ignored");
        }
        ruleFindingCount = Math.max(0, ruleFindingCount);
        llmFindingCount = Math.max(0, llmFindingCount);
        verifiedFindingCount = Math.max(0, verifiedFindingCount);
    }

    private static String normalize(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("LLM evaluation " + field + " must not be blank");
        }
        return value.trim();
    }

    private static String normalizeSeverity(String value) {
        return value == null || value.isBlank() ? "NONE" : value.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private static String normalizeRepositoryKey(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        if (!normalized.matches("[a-z0-9][a-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException(
                "LLM evaluation sourceRepositoryKey must be an anonymized token"
            );
        }
        return normalized;
    }
}
