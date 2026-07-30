package com.repoguard.agent.mapper.projection;

import java.math.BigDecimal;

public final class ReviewQualityBaselineProjections {

    private ReviewQualityBaselineProjections() {
    }

    public record Summary(
        Long totalFindings,
        Long highRiskFindings,
        Long labeledHighRiskFindings,
        Long confirmedHighRiskFindings,
        Long falsePositiveHighRiskFindings,
        Long anchoredFindings,
        Long duplicateFindings
    ) {
    }

    public record Group(
        String ruleId,
        String source,
        String repository,
        String language,
        String severity,
        Long totalFindings,
        Long confirmedValidCount,
        Long falsePositiveCount,
        Long pendingCount,
        Long anchoredCount
    ) {
    }

    public record Execution(
        Long completedTasks,
        BigDecimal averageDurationSeconds,
        BigDecimal totalLlmEstimatedCost
    ) {
    }
}
