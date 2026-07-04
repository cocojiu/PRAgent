package com.repoguard.agent.service.impl;

import com.repoguard.agent.config.CacheNames;
import com.repoguard.agent.dashboard.DashboardLlmQualityFormatter;
import com.repoguard.agent.dashboard.DashboardLlmQualityTrendBuilder;
import com.repoguard.agent.dashboard.DashboardOverviewDisplayMapper;
import com.repoguard.agent.dashboard.DashboardReviewTrendWindow;
import com.repoguard.agent.dashboard.DashboardRuleDisplayMapper;
import com.repoguard.agent.dashboard.DashboardStatusMapper;
import com.repoguard.agent.dashboard.DashboardSystemHealthProbe;
import com.repoguard.agent.dto.ChartSliceDto;
import com.repoguard.agent.dto.DashboardHighRiskReview;
import com.repoguard.agent.dto.DashboardLlmQualityModelStat;
import com.repoguard.agent.dto.DashboardLlmQualityRepositoryStat;
import com.repoguard.agent.dto.DashboardLlmQualityTrendCount;
import com.repoguard.agent.dto.DashboardMetricDto;
import com.repoguard.agent.dto.DashboardMetricStat;
import com.repoguard.agent.dto.DashboardOverviewResponse;
import com.repoguard.agent.dto.DashboardReviewTrendCount;
import com.repoguard.agent.dto.DashboardRiskLevelCount;
import com.repoguard.agent.dto.DashboardRuleHitCount;
import com.repoguard.agent.dto.FailedRuleStatDto;
import com.repoguard.agent.dto.HighRiskReviewDto;
import com.repoguard.agent.dto.LlmQualityByModelDto;
import com.repoguard.agent.dto.LlmQualityByRepositoryDto;
import com.repoguard.agent.dto.ReviewTrendPointDto;
import com.repoguard.agent.mapper.DashboardMapper;
import com.repoguard.agent.service.DashboardService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
public class DashboardServiceImpl implements DashboardService {

    private static final DateTimeFormatter REVIEWED_AT_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final DashboardMapper dashboardMapper;
    private final DashboardStatusMapper statusMapper;
    private final DashboardRuleDisplayMapper ruleDisplayMapper;
    private final DashboardOverviewDisplayMapper overviewDisplayMapper;
    private final DashboardLlmQualityFormatter llmQualityFormatter;
    private final DashboardLlmQualityTrendBuilder llmQualityTrendBuilder;
    private final DashboardReviewTrendWindow reviewTrendWindow;
    private final DashboardSystemHealthProbe systemHealthProbe;

    public DashboardServiceImpl(
        DashboardMapper dashboardMapper,
        DashboardStatusMapper statusMapper,
        DashboardRuleDisplayMapper ruleDisplayMapper,
        DashboardOverviewDisplayMapper overviewDisplayMapper,
        DashboardLlmQualityFormatter llmQualityFormatter,
        DashboardLlmQualityTrendBuilder llmQualityTrendBuilder,
        DashboardReviewTrendWindow reviewTrendWindow,
        DashboardSystemHealthProbe systemHealthProbe
    ) {
        this.dashboardMapper = dashboardMapper;
        this.statusMapper = statusMapper;
        this.ruleDisplayMapper = ruleDisplayMapper;
        this.overviewDisplayMapper = overviewDisplayMapper;
        this.llmQualityFormatter = llmQualityFormatter;
        this.llmQualityTrendBuilder = llmQualityTrendBuilder;
        this.reviewTrendWindow = reviewTrendWindow;
        this.systemHealthProbe = systemHealthProbe;
    }

    @Override
    @Cacheable(
        cacheNames = CacheNames.DASHBOARD_OVERVIEW,
        key = "T(com.repoguard.agent.dashboard.DashboardLlmTrendDays).normalize(#llmTrendDays)",
        sync = true
    )
    public DashboardOverviewResponse getOverview(Integer llmTrendDays) {
        DashboardLlmQualityTrendBuilder.Window llmTrendWindow = llmQualityTrendBuilder.window(llmTrendDays);
        LocalDate reviewTrendStartDate = reviewTrendWindow.startDate();
        DashboardMetricStats metricStats = loadMetricStats(reviewTrendStartDate);
        List<DashboardReviewTrendCount> reviewTrendCounts = dashboardMapper.selectReviewTrendCounts(reviewTrendStartDate);
        List<DashboardHighRiskReview> highRiskReviews = dashboardMapper.selectRecentHighRiskReviews(reviewTrendStartDate);
        List<DashboardLlmQualityModelStat> llmQualityByModelStats = dashboardMapper.selectLlmQualityByModelStats(reviewTrendStartDate);
        List<DashboardLlmQualityRepositoryStat> llmQualityByRepositoryStats = dashboardMapper.selectLlmQualityByRepositoryStats(reviewTrendStartDate);
        List<DashboardLlmQualityTrendCount> llmQualityTrendCounts = dashboardMapper.selectLlmQualityTrendCounts(llmTrendWindow.startDate());
        List<DashboardRuleHitCount> ruleHitCounts = dashboardMapper.selectRuleHitCounts(reviewTrendStartDate);

        return new DashboardOverviewResponse(
            buildMetrics(metricStats),
            buildTrend(reviewTrendCounts),
            buildRiskDistribution(reviewTrendStartDate),
            buildRuleHits(ruleHitCounts),
            buildHighRiskReviews(highRiskReviews),
            buildFailedRules(ruleHitCounts),
            systemHealthProbe.probe(),
            buildLlmQualityByModel(llmQualityByModelStats),
            buildLlmQualityByRepository(llmQualityByRepositoryStats),
            llmQualityTrendBuilder.build(llmQualityTrendCounts, llmTrendWindow)
        );
    }

    private DashboardMetricStats loadMetricStats(LocalDate startDate) {
        DashboardMetricStat metricStat = dashboardMapper.selectMetricStat(startDate);
        long total = metricStat == null ? 0L : safeCount(metricStat.getTotal());
        long highRisk = metricStat == null ? 0L : safeCount(metricStat.getHighRisk());
        long failed = metricStat == null ? 0L : safeCount(metricStat.getFailed());
        int averageDurationSeconds = metricStat == null ? 0 : safeAverageDuration(metricStat.getAverageDurationSeconds());
        return new DashboardMetricStats(total, highRisk, failed, averageDurationSeconds);
    }

    private List<DashboardMetricDto> buildMetrics(DashboardMetricStats stats) {
        DashboardOverviewDisplayMapper.MetricDisplay totalReviews = overviewDisplayMapper.totalReviewsMetric();
        DashboardOverviewDisplayMapper.MetricDisplay highRiskPullRequests = overviewDisplayMapper.highRiskPullRequestsMetric();
        DashboardOverviewDisplayMapper.MetricDisplay failedTasks = overviewDisplayMapper.failedTasksMetric();
        DashboardOverviewDisplayMapper.MetricDisplay averageReviewDuration = overviewDisplayMapper.averageReviewDurationMetric();
        return List.of(
            metric(totalReviews, String.valueOf(stats.total()), "0.0%"),
            metric(highRiskPullRequests, String.valueOf(stats.highRisk()), percent(stats.highRisk(), stats.total())),
            metric(failedTasks, String.valueOf(stats.failed()), percent(stats.failed(), stats.total())),
            metric(averageReviewDuration, formatDuration(stats.averageDurationSeconds()), "0.0%")
        );
    }

    private List<ReviewTrendPointDto> buildTrend(List<DashboardReviewTrendCount> reviewTrendCounts) {
        // 当前仪表盘图表直接消费展示标签，因此这里按格式化后的日期聚合。
        return nullToEmpty(reviewTrendCounts).stream()
            .map(count -> new ReviewTrendPointDto(count.getDayLabel(), safeTrendTotal(count)))
            .toList();
    }

    private List<ChartSliceDto> buildRiskDistribution(LocalDate startDate) {
        List<DashboardRiskLevelCount> riskLevelCounts = dashboardMapper.selectRiskLevelCounts(startDate);
        Map<String, Long> countByRisk = nullToEmpty(riskLevelCounts).stream()
            .collect(Collectors.toMap(DashboardRiskLevelCount::getRiskLevel, this::safeTotal, Long::sum));
        long total = countByRisk.values().stream().mapToLong(Long::longValue).sum();

        return List.of(
            riskSlice("HIGH", countByRisk.getOrDefault("HIGH", 0L), total),
            riskSlice("MEDIUM", countByRisk.getOrDefault("MEDIUM", 0L), total),
            riskSlice("LOW", countByRisk.getOrDefault("LOW", 0L), total),
            riskSlice("INFO", countByRisk.getOrDefault("INFO", 0L), total)
        );
    }

    private List<ChartSliceDto> buildRuleHits(List<DashboardRuleHitCount> ruleHitCounts) {
        long total = totalRuleHits(ruleHitCounts);
        // 没有确定规则编号的问题统一归类为 LLM 审查结果。
        return nullToEmpty(ruleHitCounts).stream()
            .sorted(Comparator.comparingLong(this::safeRuleTotal).reversed())
            .map(count -> new ChartSliceDto(
                ruleDisplayMapper.ruleName(count.getRuleId()),
                safeRuleTotal(count),
                ruleDisplayMapper.ruleColor(count.getRuleId()),
                percent(safeRuleTotal(count), total)
            ))
            .toList();
    }

    private List<HighRiskReviewDto> buildHighRiskReviews(List<DashboardHighRiskReview> highRiskReviews) {
        return nullToEmpty(highRiskReviews).stream()
            .map(review -> new HighRiskReviewDto(
                review.getTitle(),
                review.getRepository(),
                lower(review.getRiskLevel()),
                safeHighRiskRuleHits(review),
                formatReviewedAt(review.getCreatedAt()),
                statusMapper.reviewTaskStatusText(review.getStatus())
            ))
            .toList();
    }

    private List<FailedRuleStatDto> buildFailedRules(List<DashboardRuleHitCount> ruleHitCounts) {
        long total = totalRuleHits(ruleHitCounts);
        return nullToEmpty(ruleHitCounts).stream()
            .sorted(Comparator.comparingLong(this::safeRuleTotal).reversed())
            .map(count -> new FailedRuleStatDto(
                ruleDisplayMapper.ruleName(count.getRuleId()),
                safeRuleTotal(count),
                "0.0%",
                "down",
                percent(safeRuleTotal(count), total)
            ))
            .toList();
    }

    private List<LlmQualityByModelDto> buildLlmQualityByModel(List<DashboardLlmQualityModelStat> stats) {
        return nullToEmpty(stats).stream()
            .map(stat -> new LlmQualityByModelDto(
                stat.getModelLabel(),
                safeModelTaskCount(stat),
                llmQualityFormatter.averageDuration(stat.getAverageDurationMs()),
                llmQualityFormatter.averageTokens(stat.getAverageTokens()),
                llmQualityFormatter.averageCost(stat.getAverageCost()),
                llmQualityFormatter.rate(safeModelParseSuccessCount(stat), safeModelTaskCount(stat)),
                llmQualityFormatter.rate(safeModelFallbackCount(stat), safeModelTaskCount(stat)),
                llmQualityFormatter.rate(safeModelPartialFallbackCount(stat), safeModelTaskCount(stat)),
                llmQualityFormatter.rate(safeModelValidFeedbackCount(stat), safeModelReviewedFeedbackCount(stat)),
                llmQualityFormatter.rate(safeModelFalsePositiveFeedbackCount(stat), safeModelReviewedFeedbackCount(stat))
            ))
            .toList();
    }

    private List<LlmQualityByRepositoryDto> buildLlmQualityByRepository(List<DashboardLlmQualityRepositoryStat> stats) {
        return nullToEmpty(stats).stream()
            .map(stat -> new LlmQualityByRepositoryDto(
                stat.getRepositoryLabel(),
                safeRepositoryTaskCount(stat),
                llmQualityFormatter.rate(safeRepositoryFallbackCount(stat), safeRepositoryTaskCount(stat)),
                llmQualityFormatter.rate(safeRepositoryPartialFallbackCount(stat), safeRepositoryTaskCount(stat)),
                llmQualityFormatter.rate(safeRepositoryValidFeedbackCount(stat), safeRepositoryReviewedFeedbackCount(stat)),
                llmQualityFormatter.rate(safeRepositoryFalsePositiveFeedbackCount(stat), safeRepositoryReviewedFeedbackCount(stat))
            ))
            .toList();
    }

    private DashboardMetricDto metric(DashboardOverviewDisplayMapper.MetricDisplay display, String value, String trend) {
        return new DashboardMetricDto(display.label(), value, trend, display.trendType(), display.color());
    }

    private ChartSliceDto riskSlice(String riskLevel, long value, long total) {
        DashboardOverviewDisplayMapper.RiskLevelDisplay display = overviewDisplayMapper.riskLevel(riskLevel);
        return new ChartSliceDto(display.name(), value, display.color(), percent(value, total));
    }

    private String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    private String percent(long value, long total) {
        if (total == 0) {
            return "0.0%";
        }
        return String.format(Locale.ROOT, "%.1f%%", value * 100.0 / total);
    }

    private long safeCount(Long value) {
        return value == null ? 0L : value;
    }

    private int safeAverageDuration(BigDecimal value) {
        return value == null ? 0 : value.setScale(0, RoundingMode.HALF_UP).intValue();
    }

    private long safeTotal(DashboardRiskLevelCount count) {
        return count.getTotal() == null ? 0L : count.getTotal();
    }

    private long safeTrendTotal(DashboardReviewTrendCount count) {
        return count.getTotal() == null ? 0L : count.getTotal();
    }

    private long safeRuleTotal(DashboardRuleHitCount count) {
        return count.getTotal() == null ? 0L : count.getTotal();
    }

    private long safeHighRiskRuleHits(DashboardHighRiskReview review) {
        return review.getRuleHits() == null ? 0L : review.getRuleHits();
    }

    private long safeModelTaskCount(DashboardLlmQualityModelStat stat) {
        return stat.getTaskCount() == null ? 0L : stat.getTaskCount();
    }

    private long safeModelParseSuccessCount(DashboardLlmQualityModelStat stat) {
        return stat.getParseSuccessCount() == null ? 0L : stat.getParseSuccessCount();
    }

    private long safeModelFallbackCount(DashboardLlmQualityModelStat stat) {
        return stat.getFallbackCount() == null ? 0L : stat.getFallbackCount();
    }

    private long safeModelPartialFallbackCount(DashboardLlmQualityModelStat stat) {
        return stat.getPartialFallbackCount() == null ? 0L : stat.getPartialFallbackCount();
    }

    private long safeModelReviewedFeedbackCount(DashboardLlmQualityModelStat stat) {
        return stat.getReviewedFeedbackCount() == null ? 0L : stat.getReviewedFeedbackCount();
    }

    private long safeModelValidFeedbackCount(DashboardLlmQualityModelStat stat) {
        return stat.getValidFeedbackCount() == null ? 0L : stat.getValidFeedbackCount();
    }

    private long safeModelFalsePositiveFeedbackCount(DashboardLlmQualityModelStat stat) {
        return stat.getFalsePositiveFeedbackCount() == null ? 0L : stat.getFalsePositiveFeedbackCount();
    }

    private long safeRepositoryTaskCount(DashboardLlmQualityRepositoryStat stat) {
        return stat.getTaskCount() == null ? 0L : stat.getTaskCount();
    }

    private long safeRepositoryFallbackCount(DashboardLlmQualityRepositoryStat stat) {
        return stat.getFallbackCount() == null ? 0L : stat.getFallbackCount();
    }

    private long safeRepositoryPartialFallbackCount(DashboardLlmQualityRepositoryStat stat) {
        return stat.getPartialFallbackCount() == null ? 0L : stat.getPartialFallbackCount();
    }

    private long safeRepositoryReviewedFeedbackCount(DashboardLlmQualityRepositoryStat stat) {
        return stat.getReviewedFeedbackCount() == null ? 0L : stat.getReviewedFeedbackCount();
    }

    private long safeRepositoryValidFeedbackCount(DashboardLlmQualityRepositoryStat stat) {
        return stat.getValidFeedbackCount() == null ? 0L : stat.getValidFeedbackCount();
    }

    private long safeRepositoryFalsePositiveFeedbackCount(DashboardLlmQualityRepositoryStat stat) {
        return stat.getFalsePositiveFeedbackCount() == null ? 0L : stat.getFalsePositiveFeedbackCount();
    }

    private String formatReviewedAt(LocalDateTime reviewedAt) {
        return reviewedAt == null ? "" : reviewedAt.format(REVIEWED_AT_FORMATTER);
    }

    private long totalRuleHits(List<DashboardRuleHitCount> ruleHitCounts) {
        return nullToEmpty(ruleHitCounts).stream().mapToLong(this::safeRuleTotal).sum();
    }

    private <T> List<T> nullToEmpty(List<T> values) {
        return values == null ? List.of() : values;
    }

    private String formatDuration(int durationSeconds) {
        int minutes = durationSeconds / 60;
        int seconds = durationSeconds % 60;
        return minutes + "分 " + seconds + "秒";
    }

    private record DashboardMetricStats(long total, long highRisk, long failed, int averageDurationSeconds) {
    }
}
