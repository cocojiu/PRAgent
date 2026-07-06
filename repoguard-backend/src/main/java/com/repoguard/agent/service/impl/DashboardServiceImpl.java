package com.repoguard.agent.service.impl;

import com.repoguard.agent.config.CacheNames;
import com.repoguard.agent.dashboard.DashboardHighRiskReviewAssembler;
import com.repoguard.agent.dashboard.DashboardLlmQualityFormatter;
import com.repoguard.agent.dashboard.DashboardLlmQualityStatsAssembler;
import com.repoguard.agent.dashboard.DashboardLlmQualityTrendBuilder;
import com.repoguard.agent.dashboard.DashboardMetricAssembler;
import com.repoguard.agent.dashboard.DashboardOverviewDisplayMapper;
import com.repoguard.agent.dashboard.DashboardRiskDistributionAssembler;
import com.repoguard.agent.dashboard.DashboardReviewTrendWindow;
import com.repoguard.agent.dashboard.DashboardRuleAssembler;
import com.repoguard.agent.dashboard.DashboardRuleDisplayMapper;
import com.repoguard.agent.dashboard.DashboardStatusMapper;
import com.repoguard.agent.dashboard.DashboardSystemHealthProbe;
import com.repoguard.agent.dto.ChartSliceDto;
import com.repoguard.agent.dto.DashboardHighRiskReview;
import com.repoguard.agent.dto.DashboardLlmQualityModelStat;
import com.repoguard.agent.dto.DashboardLlmQualityRepositoryStat;
import com.repoguard.agent.dto.DashboardLlmQualityResponse;
import com.repoguard.agent.dto.DashboardLlmQualityTrendCount;
import com.repoguard.agent.dto.DashboardMetricDto;
import com.repoguard.agent.dto.DashboardOverviewResponse;
import com.repoguard.agent.dto.DashboardReviewTrendCount;
import com.repoguard.agent.dto.DashboardRiskLevelCount;
import com.repoguard.agent.dto.DashboardRulesResponse;
import com.repoguard.agent.dto.DashboardRuleHitCount;
import com.repoguard.agent.dto.FailedRuleStatDto;
import com.repoguard.agent.dto.HighRiskReviewDto;
import com.repoguard.agent.dto.ReviewTrendPointDto;
import com.repoguard.agent.dto.SystemHealthItemDto;
import com.repoguard.agent.mapper.DashboardMapper;
import com.repoguard.agent.service.DashboardService;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
public class DashboardServiceImpl implements DashboardService {

    private final DashboardMapper dashboardMapper;
    private final DashboardStatusMapper statusMapper;
    private final DashboardRuleDisplayMapper ruleDisplayMapper;
    private final DashboardOverviewDisplayMapper overviewDisplayMapper;
    private final DashboardMetricAssembler dashboardMetricAssembler;
    private final DashboardRiskDistributionAssembler riskDistributionAssembler;
    private final DashboardRuleAssembler dashboardRuleAssembler;
    private final DashboardHighRiskReviewAssembler highRiskReviewAssembler;
    private final DashboardLlmQualityStatsAssembler llmQualityStatsAssembler;
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
        this.dashboardMetricAssembler = new DashboardMetricAssembler(overviewDisplayMapper);
        this.riskDistributionAssembler = new DashboardRiskDistributionAssembler(overviewDisplayMapper);
        this.dashboardRuleAssembler = new DashboardRuleAssembler(ruleDisplayMapper);
        this.highRiskReviewAssembler = new DashboardHighRiskReviewAssembler(statusMapper);
        this.llmQualityStatsAssembler = new DashboardLlmQualityStatsAssembler(llmQualityFormatter);
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
        LocalDate latestReviewDate = latestReviewDate();
        DashboardLlmQualityTrendBuilder.Window llmTrendWindow = llmQualityTrendBuilder.window(llmTrendDays, latestReviewDate);
        LocalDate reviewTrendStartDate = reviewTrendWindow.startDate(latestReviewDate);
        DashboardRulesResponse rules = buildRules(dashboardMapper.selectRuleHitCounts(reviewTrendStartDate));
        DashboardLlmQualityResponse llmQuality = buildLlmQuality(reviewTrendStartDate, llmTrendWindow);

        return new DashboardOverviewResponse(
            dashboardMetricAssembler.assemble(dashboardMapper.selectMetricStat(reviewTrendStartDate)),
            buildTrend(dashboardMapper.selectReviewTrendCounts(reviewTrendStartDate)),
            buildRiskDistribution(reviewTrendStartDate),
            rules.ruleHits(),
            buildHighRiskReviews(dashboardMapper.selectRecentHighRiskReviews(reviewTrendStartDate)),
            rules.failedRules(),
            List.of(),
            llmQuality.byModel(),
            llmQuality.byRepository(),
            llmQuality.trend()
        );
    }

    @Override
    @Cacheable(cacheNames = CacheNames.DASHBOARD_SUMMARY, key = "'summary'", sync = true)
    public List<DashboardMetricDto> getSummary() {
        return dashboardMetricAssembler.assemble(dashboardMapper.selectMetricStat(reviewTrendStartDate()));
    }

    @Override
    @Cacheable(cacheNames = CacheNames.DASHBOARD_REVIEW_TREND, key = "'reviewTrend'", sync = true)
    public List<ReviewTrendPointDto> getReviewTrend() {
        return buildTrend(dashboardMapper.selectReviewTrendCounts(reviewTrendStartDate()));
    }

    @Override
    @Cacheable(cacheNames = CacheNames.DASHBOARD_RISK_DISTRIBUTION, key = "'riskDistribution'", sync = true)
    public List<ChartSliceDto> getRiskDistribution() {
        return buildRiskDistribution(reviewTrendStartDate());
    }

    @Override
    @Cacheable(cacheNames = CacheNames.DASHBOARD_RULES, key = "'rules'", sync = true)
    public DashboardRulesResponse getRules() {
        return buildRules(dashboardMapper.selectRuleHitCounts(reviewTrendStartDate()));
    }

    @Override
    @Cacheable(cacheNames = CacheNames.DASHBOARD_HIGH_RISK_REVIEWS, key = "'highRiskReviews'", sync = true)
    public List<HighRiskReviewDto> getHighRiskReviews() {
        return buildHighRiskReviews(dashboardMapper.selectRecentHighRiskReviews(reviewTrendStartDate()));
    }

    @Override
    @Cacheable(
        cacheNames = CacheNames.DASHBOARD_LLM_QUALITY,
        key = "T(com.repoguard.agent.dashboard.DashboardLlmTrendDays).normalize(#llmTrendDays)",
        sync = true
    )
    public DashboardLlmQualityResponse getLlmQuality(Integer llmTrendDays) {
        LocalDate latestReviewDate = latestReviewDate();
        return buildLlmQuality(
            reviewTrendWindow.startDate(latestReviewDate),
            llmQualityTrendBuilder.window(llmTrendDays, latestReviewDate)
        );
    }

    @Override
    public List<SystemHealthItemDto> getSystemHealth() {
        return systemHealthProbe.probe();
    }

    private LocalDate reviewTrendStartDate() {
        return reviewTrendWindow.startDate(latestReviewDate());
    }

    private LocalDate latestReviewDate() {
        return dashboardMapper.selectLatestReviewTaskDate();
    }

    private List<ReviewTrendPointDto> buildTrend(List<DashboardReviewTrendCount> reviewTrendCounts) {
        // 当前仪表盘图表直接消费展示标签，因此这里按格式化后的日期聚合。
        return nullToEmpty(reviewTrendCounts).stream()
            .map(count -> new ReviewTrendPointDto(count.getDayLabel(), safeTrendTotal(count)))
            .toList();
    }

    private List<ChartSliceDto> buildRiskDistribution(LocalDate startDate) {
        return riskDistributionAssembler.assemble(dashboardMapper.selectRiskLevelCounts(startDate));
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
        return highRiskReviewAssembler.assemble(highRiskReviews);
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

    private DashboardRulesResponse buildRules(List<DashboardRuleHitCount> ruleHitCounts) {
        return dashboardRuleAssembler.assemble(ruleHitCounts);
    }

    private DashboardLlmQualityResponse buildLlmQuality(
        LocalDate reviewTrendStartDate,
        DashboardLlmQualityTrendBuilder.Window llmTrendWindow
    ) {
        List<DashboardLlmQualityModelStat> modelStats = dashboardMapper.selectLlmQualityByModelStats(reviewTrendStartDate);
        List<DashboardLlmQualityRepositoryStat> repositoryStats = dashboardMapper.selectLlmQualityByRepositoryStats(reviewTrendStartDate);
        List<DashboardLlmQualityTrendCount> trendCounts = dashboardMapper.selectLlmQualityTrendCounts(llmTrendWindow.startDate());
        return new DashboardLlmQualityResponse(
            llmQualityStatsAssembler.assembleByModel(modelStats),
            llmQualityStatsAssembler.assembleByRepository(repositoryStats),
            llmQualityTrendBuilder.build(trendCounts, llmTrendWindow)
        );
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

    private long safeTrendTotal(DashboardReviewTrendCount count) {
        return count.getTotal() == null ? 0L : count.getTotal();
    }

    private long safeRuleTotal(DashboardRuleHitCount count) {
        return count.getTotal() == null ? 0L : count.getTotal();
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
