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
    BigDecimal estimatedCost
) {

    public LlmEvaluationObservation {
        caseId = normalize(caseId, "caseId");
        category = normalize(category, "category");
        expectedSeverity = normalizeSeverity(expectedSeverity);
        predictedSeverity = normalizeSeverity(predictedSeverity);
        predictionKey = predictionKey == null ? "" : predictionKey.trim();
        latencyMs = Math.max(0, latencyMs);
        totalTokens = Math.max(0, totalTokens);
        estimatedCost = estimatedCost == null ? BigDecimal.ZERO : estimatedCost.max(BigDecimal.ZERO);
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
