package com.repoguard.agent.review.quality;

import java.math.BigDecimal;
import java.util.List;

public record ReviewQualityBaseline(
    long totalFindings,
    long highRiskFindings,
    BigDecimal highRiskRate,
    long labeledHighRiskFindings,
    long confirmedHighRiskFindings,
    long falsePositiveHighRiskFindings,
    BigDecimal labeledHighRiskPrecision,
    BigDecimal labeledHighRiskFalsePositiveRate,
    long anchoredFindings,
    BigDecimal anchorRate,
    long duplicateFindings,
    BigDecimal duplicateRate,
    long completedTasks,
    BigDecimal averageDurationSeconds,
    BigDecimal totalLlmEstimatedCost,
    List<ReviewQualityGroupBaseline> groups
) {
}
