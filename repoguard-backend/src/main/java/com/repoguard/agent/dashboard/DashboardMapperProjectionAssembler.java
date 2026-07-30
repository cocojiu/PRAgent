package com.repoguard.agent.dashboard;

import com.repoguard.agent.dto.DashboardHighRiskReview;
import com.repoguard.agent.dto.DashboardLlmQualityModelStat;
import com.repoguard.agent.dto.DashboardLlmQualityRepositoryStat;
import com.repoguard.agent.dto.DashboardLlmQualityTrendCount;
import com.repoguard.agent.dto.DashboardMetricStat;
import com.repoguard.agent.dto.DashboardReviewTrendCount;
import com.repoguard.agent.dto.DashboardRiskLevelCount;
import com.repoguard.agent.dto.DashboardRuleHitCount;
import com.repoguard.agent.mapper.projection.DashboardProjections;
import java.util.List;

public final class DashboardMapperProjectionAssembler {

    private DashboardMapperProjectionAssembler() {
    }

    public static DashboardMetricStat toDto(DashboardProjections.MetricStat source) {
        if (source == null) {
            return null;
        }
        DashboardMetricStat target = new DashboardMetricStat();
        target.setTotal(source.total());
        target.setHighRisk(source.highRisk());
        target.setFailed(source.failed());
        target.setAverageDurationSeconds(source.averageDurationSeconds());
        return target;
    }

    public static List<DashboardRiskLevelCount> toRiskLevelDtos(
        List<DashboardProjections.RiskLevelCount> sources
    ) {
        return map(sources, source -> {
            DashboardRiskLevelCount target = new DashboardRiskLevelCount();
            target.setRiskLevel(source.riskLevel());
            target.setTotal(source.total());
            return target;
        });
    }

    public static List<DashboardReviewTrendCount> toReviewTrendDtos(
        List<DashboardProjections.ReviewTrendCount> sources
    ) {
        return map(sources, source -> {
            DashboardReviewTrendCount target = new DashboardReviewTrendCount();
            target.setDayLabel(source.dayLabel());
            target.setTotal(source.total());
            return target;
        });
    }

    public static List<DashboardRuleHitCount> toRuleHitDtos(List<DashboardProjections.RuleHitCount> sources) {
        return map(sources, source -> {
            DashboardRuleHitCount target = new DashboardRuleHitCount();
            target.setRuleId(source.ruleId());
            target.setTotal(source.total());
            return target;
        });
    }

    public static List<DashboardHighRiskReview> toHighRiskReviewDtos(
        List<DashboardProjections.HighRiskReview> sources
    ) {
        return map(sources, source -> {
            DashboardHighRiskReview target = new DashboardHighRiskReview();
            target.setTitle(source.title());
            target.setRepository(source.repository());
            target.setRiskLevel(source.riskLevel());
            target.setRuleHits(source.ruleHits());
            target.setCreatedAt(source.createdAt());
            target.setStatus(source.status());
            return target;
        });
    }

    public static List<DashboardLlmQualityTrendCount> toLlmQualityTrendDtos(
        List<DashboardProjections.LlmQualityTrendCount> sources
    ) {
        return map(sources, source -> {
            DashboardLlmQualityTrendCount target = new DashboardLlmQualityTrendCount();
            target.setDayKey(source.dayKey());
            target.setTaskCount(source.taskCount());
            target.setParseSuccessCount(source.parseSuccessCount());
            target.setFallbackCount(source.fallbackCount());
            target.setPartialFallbackCount(source.partialFallbackCount());
            return target;
        });
    }

    public static List<DashboardLlmQualityModelStat> toLlmQualityModelDtos(
        List<DashboardProjections.LlmQualityModelStat> sources
    ) {
        return map(sources, source -> {
            DashboardLlmQualityModelStat target = new DashboardLlmQualityModelStat();
            target.setModelLabel(source.modelLabel());
            target.setTaskCount(source.taskCount());
            target.setAverageDurationMs(source.averageDurationMs());
            target.setAverageTokens(source.averageTokens());
            target.setAverageCost(source.averageCost());
            target.setParseSuccessCount(source.parseSuccessCount());
            target.setFallbackCount(source.fallbackCount());
            target.setPartialFallbackCount(source.partialFallbackCount());
            target.setReviewedFeedbackCount(source.reviewedFeedbackCount());
            target.setValidFeedbackCount(source.validFeedbackCount());
            target.setFalsePositiveFeedbackCount(source.falsePositiveFeedbackCount());
            return target;
        });
    }

    public static List<DashboardLlmQualityRepositoryStat> toLlmQualityRepositoryDtos(
        List<DashboardProjections.LlmQualityRepositoryStat> sources
    ) {
        return map(sources, source -> {
            DashboardLlmQualityRepositoryStat target = new DashboardLlmQualityRepositoryStat();
            target.setRepositoryLabel(source.repositoryLabel());
            target.setTaskCount(source.taskCount());
            target.setFallbackCount(source.fallbackCount());
            target.setPartialFallbackCount(source.partialFallbackCount());
            target.setReviewedFeedbackCount(source.reviewedFeedbackCount());
            target.setValidFeedbackCount(source.validFeedbackCount());
            target.setFalsePositiveFeedbackCount(source.falsePositiveFeedbackCount());
            return target;
        });
    }

    private static <S, T> List<T> map(List<S> sources, java.util.function.Function<S, T> mapper) {
        return sources == null ? null : sources.stream().map(mapper).toList();
    }
}
