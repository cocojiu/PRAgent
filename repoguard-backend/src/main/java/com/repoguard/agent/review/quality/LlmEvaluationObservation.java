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
    long verifiedFindingCount
) {

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
            0
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
}
