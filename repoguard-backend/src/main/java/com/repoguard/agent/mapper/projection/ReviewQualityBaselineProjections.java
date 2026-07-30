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
        String versionKey,
        String detectorVersion,
        Long ruleConfigVersion,
        Long policyVersion,
        String promptVersion,
        String contextVersion,
        String schemaVersion,
        String verifierVersion,
        String aggregationVersion,
        Long totalFindings,
        Long labeledCount,
        Long confirmedValidCount,
        Long falsePositiveCount,
        Long pendingCount,
        Long highRiskCount,
        Long blockingCount,
        Long revokedBlockingCount,
        Long anchoredCount,
        Long duplicateCount
    ) {
        public Group(
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
            this(
                ruleId,
                source,
                repository,
                language,
                severity,
                "legacy-detector-v1|rule=1|prompt=not-applicable|aggregation=server-risk-v2",
                "legacy-detector-v1",
                1L,
                1L,
                "not-applicable",
                "not-applicable",
                "not-applicable",
                "not-applicable",
                "server-risk-v2",
                totalFindings,
                value(confirmedValidCount) + value(falsePositiveCount),
                confirmedValidCount,
                falsePositiveCount,
                pendingCount,
                isHighRisk(severity) ? totalFindings : 0L,
                0L,
                0L,
                anchoredCount,
                0L
            );
        }

        private static long value(Long value) {
            return value == null ? 0L : value;
        }

        private static boolean isHighRisk(String severity) {
            return "HIGH".equalsIgnoreCase(severity) || "CRITICAL".equalsIgnoreCase(severity);
        }
    }

    public record Execution(
        Long completedTasks,
        BigDecimal averageDurationSeconds,
        BigDecimal totalLlmEstimatedCost
    ) {
    }
}
