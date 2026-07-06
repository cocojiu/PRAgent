package com.repoguard.agent.service.impl;

import com.repoguard.agent.config.CacheNames;
import com.repoguard.agent.dashboard.DashboardHighRiskReviewAssembler;
import com.repoguard.agent.dashboard.DashboardLlmTrendDays;
import com.repoguard.agent.dashboard.DashboardLlmQualityStatsAssembler;
import com.repoguard.agent.dashboard.DashboardLlmQualityTrendBuilder;
import com.repoguard.agent.dashboard.DashboardMetricAssembler;
import com.repoguard.agent.dashboard.DashboardReviewTrendAssembler;
import com.repoguard.agent.dashboard.DashboardRiskDistributionAssembler;
import com.repoguard.agent.dashboard.DashboardReviewTrendWindow;
import com.repoguard.agent.dashboard.DashboardRuleAssembler;
import com.repoguard.agent.dashboard.DashboardSnapshotStore;
import com.repoguard.agent.dashboard.DashboardSystemHealthProbe;
import com.repoguard.agent.dto.ChartSliceDto;
import com.repoguard.agent.dto.DashboardHighRiskReview;
import com.repoguard.agent.dto.DashboardLlmQualityModelStat;
import com.repoguard.agent.dto.DashboardLlmQualityRepositoryStat;
import com.repoguard.agent.dto.DashboardLlmQualityResponse;
import com.repoguard.agent.dto.DashboardLlmQualityTrendCount;
import com.repoguard.agent.dto.DashboardMetricDto;
import com.repoguard.agent.dto.DashboardOverviewResponse;
import com.repoguard.agent.dto.DashboardRulesResponse;
import com.repoguard.agent.dto.DashboardRuleHitCount;
import com.repoguard.agent.dto.HighRiskReviewDto;
import com.repoguard.agent.dto.ReviewTrendPointDto;
import com.repoguard.agent.dto.SystemHealthItemDto;
import com.repoguard.agent.mapper.DashboardMapper;
import com.repoguard.agent.service.DashboardService;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
public class DashboardServiceImpl implements DashboardService {

    private final DashboardMapper dashboardMapper;
    private final DashboardMetricAssembler dashboardMetricAssembler;
    private final DashboardReviewTrendAssembler reviewTrendAssembler;
    private final DashboardRiskDistributionAssembler riskDistributionAssembler;
    private final DashboardRuleAssembler dashboardRuleAssembler;
    private final DashboardHighRiskReviewAssembler highRiskReviewAssembler;
    private final DashboardLlmQualityStatsAssembler llmQualityStatsAssembler;
    private final DashboardLlmQualityTrendBuilder llmQualityTrendBuilder;
    private final DashboardReviewTrendWindow reviewTrendWindow;
    private final DashboardSystemHealthProbe systemHealthProbe;
    private final DashboardSnapshotStore snapshotStore;

    public DashboardServiceImpl(
        DashboardMapper dashboardMapper,
        DashboardMetricAssembler dashboardMetricAssembler,
        DashboardReviewTrendAssembler reviewTrendAssembler,
        DashboardRiskDistributionAssembler riskDistributionAssembler,
        DashboardRuleAssembler dashboardRuleAssembler,
        DashboardHighRiskReviewAssembler highRiskReviewAssembler,
        DashboardLlmQualityStatsAssembler llmQualityStatsAssembler,
        DashboardLlmQualityTrendBuilder llmQualityTrendBuilder,
        DashboardReviewTrendWindow reviewTrendWindow,
        DashboardSystemHealthProbe systemHealthProbe,
        DashboardSnapshotStore snapshotStore
    ) {
        this.dashboardMapper = Objects.requireNonNull(dashboardMapper, "dashboardMapper must not be null");
        this.dashboardMetricAssembler =
            Objects.requireNonNull(dashboardMetricAssembler, "dashboardMetricAssembler must not be null");
        this.reviewTrendAssembler =
            Objects.requireNonNull(reviewTrendAssembler, "reviewTrendAssembler must not be null");
        this.riskDistributionAssembler =
            Objects.requireNonNull(riskDistributionAssembler, "riskDistributionAssembler must not be null");
        this.dashboardRuleAssembler =
            Objects.requireNonNull(dashboardRuleAssembler, "dashboardRuleAssembler must not be null");
        this.highRiskReviewAssembler =
            Objects.requireNonNull(highRiskReviewAssembler, "highRiskReviewAssembler must not be null");
        this.llmQualityStatsAssembler =
            Objects.requireNonNull(llmQualityStatsAssembler, "llmQualityStatsAssembler must not be null");
        this.llmQualityTrendBuilder =
            Objects.requireNonNull(llmQualityTrendBuilder, "llmQualityTrendBuilder must not be null");
        this.reviewTrendWindow = Objects.requireNonNull(reviewTrendWindow, "reviewTrendWindow must not be null");
        this.systemHealthProbe = Objects.requireNonNull(systemHealthProbe, "systemHealthProbe must not be null");
        this.snapshotStore = Objects.requireNonNull(snapshotStore, "snapshotStore must not be null");
    }

    @Override
    @Cacheable(
        cacheNames = CacheNames.DASHBOARD_OVERVIEW,
        key = "T(com.repoguard.agent.dashboard.DashboardLlmTrendDays).normalize(#llmTrendDays)",
        sync = true
    )
    public DashboardOverviewResponse getOverview(Integer llmTrendDays) {
        return snapshotStore.getOrLoad(
            CacheNames.DASHBOARD_OVERVIEW + ":" + DashboardLlmTrendDays.normalize(llmTrendDays),
            () -> buildOverview(llmTrendDays)
        );
    }

    private DashboardOverviewResponse buildOverview(Integer llmTrendDays) {
        LocalDate latestReviewDate = latestReviewDate();
        DashboardLlmQualityTrendBuilder.Window llmTrendWindow = llmQualityTrendBuilder.window(llmTrendDays, latestReviewDate);
        LocalDate reviewTrendStartDate = reviewTrendWindow.startDate(latestReviewDate);
        DashboardRulesResponse rules = buildRules(dashboardMapper.selectRuleHitCounts(reviewTrendStartDate));
        DashboardLlmQualityResponse llmQuality = buildLlmQuality(reviewTrendStartDate, llmTrendWindow);

        return new DashboardOverviewResponse(
            dashboardMetricAssembler.assemble(dashboardMapper.selectMetricStat(reviewTrendStartDate)),
            reviewTrendAssembler.assemble(dashboardMapper.selectReviewTrendCounts(reviewTrendStartDate)),
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
        return snapshotStore.getOrLoad(
            CacheNames.DASHBOARD_SUMMARY + ":summary",
            () -> dashboardMetricAssembler.assemble(dashboardMapper.selectMetricStat(reviewTrendStartDate()))
        );
    }

    @Override
    @Cacheable(cacheNames = CacheNames.DASHBOARD_REVIEW_TREND, key = "'reviewTrend'", sync = true)
    public List<ReviewTrendPointDto> getReviewTrend() {
        return snapshotStore.getOrLoad(
            CacheNames.DASHBOARD_REVIEW_TREND + ":reviewTrend",
            () -> reviewTrendAssembler.assemble(dashboardMapper.selectReviewTrendCounts(reviewTrendStartDate()))
        );
    }

    @Override
    @Cacheable(cacheNames = CacheNames.DASHBOARD_RISK_DISTRIBUTION, key = "'riskDistribution'", sync = true)
    public List<ChartSliceDto> getRiskDistribution() {
        return snapshotStore.getOrLoad(
            CacheNames.DASHBOARD_RISK_DISTRIBUTION + ":riskDistribution",
            () -> buildRiskDistribution(reviewTrendStartDate())
        );
    }

    @Override
    @Cacheable(cacheNames = CacheNames.DASHBOARD_RULES, key = "'rules'", sync = true)
    public DashboardRulesResponse getRules() {
        return snapshotStore.getOrLoad(
            CacheNames.DASHBOARD_RULES + ":rules",
            () -> buildRules(dashboardMapper.selectRuleHitCounts(reviewTrendStartDate()))
        );
    }

    @Override
    @Cacheable(cacheNames = CacheNames.DASHBOARD_HIGH_RISK_REVIEWS, key = "'highRiskReviews'", sync = true)
    public List<HighRiskReviewDto> getHighRiskReviews() {
        return snapshotStore.getOrLoad(
            CacheNames.DASHBOARD_HIGH_RISK_REVIEWS + ":highRiskReviews",
            () -> buildHighRiskReviews(dashboardMapper.selectRecentHighRiskReviews(reviewTrendStartDate()))
        );
    }

    @Override
    @Cacheable(
        cacheNames = CacheNames.DASHBOARD_LLM_QUALITY,
        key = "T(com.repoguard.agent.dashboard.DashboardLlmTrendDays).normalize(#llmTrendDays)",
        sync = true
    )
    public DashboardLlmQualityResponse getLlmQuality(Integer llmTrendDays) {
        return snapshotStore.getOrLoad(
            CacheNames.DASHBOARD_LLM_QUALITY + ":" + DashboardLlmTrendDays.normalize(llmTrendDays),
            () -> buildLlmQuality(llmTrendDays)
        );
    }

    private DashboardLlmQualityResponse buildLlmQuality(Integer llmTrendDays) {
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

    private List<ChartSliceDto> buildRiskDistribution(LocalDate startDate) {
        return riskDistributionAssembler.assemble(dashboardMapper.selectRiskLevelCounts(startDate));
    }

    private List<HighRiskReviewDto> buildHighRiskReviews(List<DashboardHighRiskReview> highRiskReviews) {
        return highRiskReviewAssembler.assemble(highRiskReviews);
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
}
