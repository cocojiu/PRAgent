package com.repoguard.agent.review.quality;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Comparable, provider-specific quality and cost report for a fixed evaluation version. */
public record LlmEvaluationReport(
    LlmEvaluationVersion version,
    String sampleFingerprint,
    int totalSamples,
    int expectedFindings,
    int predictedFindings,
    int truePositives,
    int falsePositives,
    int falseNegatives,
    BigDecimal precision,
    BigDecimal recall,
    BigDecimal precisionWilsonLowerBound,
    BigDecimal anchorRate,
    BigDecimal duplicateRate,
    BigDecimal parseFailureRate,
    Map<String, Map<String, Long>> severityConfusion,
    long totalLatencyMs,
    long totalTokens,
    BigDecimal totalCost,
    List<String> blockers,
    boolean eligible
) {

    public LlmEvaluationReport {
        blockers = blockers == null ? List.of() : List.copyOf(blockers);
        severityConfusion = severityConfusion == null ? Map.of() : Map.copyOf(severityConfusion);
        totalCost = totalCost == null ? BigDecimal.ZERO : totalCost;
    }

    public boolean qualityGatePassed() {
        return eligible && blockers.isEmpty();
    }

    public String versionKey() {
        return version.versionKey();
    }
}
