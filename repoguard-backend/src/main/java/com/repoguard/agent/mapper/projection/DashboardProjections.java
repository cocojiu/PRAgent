package com.repoguard.agent.mapper.projection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public final class DashboardProjections {

    private DashboardProjections() {
    }

    public record MetricStat(
        Long total,
        Long highRisk,
        Long failed,
        BigDecimal averageDurationSeconds
    ) {
    }

    public record RiskLevelCount(String riskLevel, Long total) {
    }

    public record ReviewTrendCount(String dayLabel, Long total) {
    }

    public record RuleHitCount(String ruleId, Long total) {
    }

    public record HighRiskReview(
        String title,
        String repository,
        String riskLevel,
        Long ruleHits,
        LocalDateTime createdAt,
        String status
    ) {
    }

    public record LlmQualityTrendCount(
        String dayKey,
        Long taskCount,
        Long parseSuccessCount,
        Long fallbackCount,
        Long partialFallbackCount
    ) {
    }

    public record LlmQualityModelStat(
        String modelLabel,
        Long taskCount,
        BigDecimal averageDurationMs,
        BigDecimal averageTokens,
        BigDecimal averageCost,
        Long parseSuccessCount,
        Long fallbackCount,
        Long partialFallbackCount,
        Long reviewedFeedbackCount,
        Long validFeedbackCount,
        Long falsePositiveFeedbackCount
    ) {
    }

    public record LlmQualityRepositoryStat(
        String repositoryLabel,
        Long taskCount,
        Long fallbackCount,
        Long partialFallbackCount,
        Long reviewedFeedbackCount,
        Long validFeedbackCount,
        Long falsePositiveFeedbackCount
    ) {
    }

    public record SnapshotRefreshState(
        LocalDate statDate,
        Long reviewVersion,
        Long reviewRefreshedVersion,
        Long llmQualityVersion,
        Long llmQualityRefreshedVersion
    ) {

        public boolean reviewDirty() {
            return reviewVersion > reviewRefreshedVersion;
        }

        public boolean llmQualityDirty() {
            return llmQualityVersion > llmQualityRefreshedVersion;
        }
    }
}
