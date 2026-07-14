package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.config.CacheNames;
import com.repoguard.agent.config.GithubIntegrationProvider;
import com.repoguard.agent.config.GithubIntegrationSettings;
import com.repoguard.agent.config.ReviewPolicyProvider;
import com.repoguard.agent.config.ReviewPolicySettings;
import com.repoguard.agent.dashboard.DashboardDailySnapshotService;
import com.repoguard.agent.dashboard.DashboardHighRiskReviewAssembler;
import com.repoguard.agent.dashboard.DashboardLlmQualityFormatter;
import com.repoguard.agent.dashboard.DashboardLlmQualityStatsAssembler;
import com.repoguard.agent.dashboard.DashboardLlmQualityTrendBuilder;
import com.repoguard.agent.dashboard.DashboardMetricAssembler;
import com.repoguard.agent.dashboard.DashboardOverviewDisplayMapper;
import com.repoguard.agent.dashboard.DashboardReviewTrendWindow;
import com.repoguard.agent.dashboard.DashboardReviewTrendAssembler;
import com.repoguard.agent.dashboard.DashboardRiskDistributionAssembler;
import com.repoguard.agent.dashboard.DashboardRuleAssembler;
import com.repoguard.agent.dashboard.DashboardRuleDisplayMapper;
import com.repoguard.agent.dashboard.DashboardSnapshotStore;
import com.repoguard.agent.dashboard.DashboardStatusMapper;
import com.repoguard.agent.dashboard.DashboardSystemHealthProbe;
import com.repoguard.agent.dto.DashboardHighRiskReview;
import com.repoguard.agent.dto.DashboardLlmQualityModelStat;
import com.repoguard.agent.dto.DashboardLlmQualityRepositoryStat;
import com.repoguard.agent.dto.DashboardLlmQualityTrendCount;
import com.repoguard.agent.dto.DashboardMetricStat;
import com.repoguard.agent.dto.DashboardReviewTrendCount;
import com.repoguard.agent.dto.DashboardRiskLevelCount;
import com.repoguard.agent.dto.DashboardRuleHitCount;
import com.repoguard.agent.dto.SystemHealthItemDto;
import com.repoguard.agent.mapper.DashboardMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.ChannelCallback;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.cache.annotation.Cacheable;

class DashboardServiceImplTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-06-19T10:00:00Z"), ZoneId.of("UTC"));

    private final DashboardMapper dashboardMapper = org.mockito.Mockito.mock(DashboardMapper.class);
    private final DashboardDailySnapshotService dailySnapshotService =
        org.mockito.Mockito.mock(DashboardDailySnapshotService.class);
    private final GithubIntegrationProvider githubIntegrationProvider = org.mockito.Mockito.mock(GithubIntegrationProvider.class);
    private final ReviewPolicyProvider reviewPolicyProvider = org.mockito.Mockito.mock(ReviewPolicyProvider.class);
    private final RabbitTemplate rabbitTemplate = org.mockito.Mockito.mock(RabbitTemplate.class);
    private final RabbitRuntimeHealthProbe rabbitRuntimeHealthProbe =
        org.mockito.Mockito.mock(RabbitRuntimeHealthProbe.class);
    private final DashboardStatusMapper statusMapper = new DashboardStatusMapper();
    private final DashboardRuleDisplayMapper ruleDisplayMapper = new DashboardRuleDisplayMapper();
    private final DashboardOverviewDisplayMapper overviewDisplayMapper = new DashboardOverviewDisplayMapper();
    private final DashboardLlmQualityFormatter llmQualityFormatter = new DashboardLlmQualityFormatter();
    private final DashboardLlmQualityTrendBuilder llmQualityTrendBuilder = DashboardLlmQualityTrendBuilder.forTest(
        llmQualityFormatter,
        FIXED_CLOCK
    );
    private final DashboardReviewTrendWindow reviewTrendWindow = DashboardReviewTrendWindow.forTest(FIXED_CLOCK);
    private final DashboardSystemHealthProbe systemHealthProbe = new DashboardSystemHealthProbe(
        githubIntegrationProvider,
        reviewPolicyProvider,
        rabbitRuntimeHealthProbe,
        statusMapper
    );
    private final DashboardSnapshotStore snapshotStore = new DashboardSnapshotStore(Runnable::run);
    private final DashboardServiceImpl service = new DashboardServiceImpl(
        dashboardMapper,
        new DashboardMetricAssembler(overviewDisplayMapper),
        new DashboardReviewTrendAssembler(),
        new DashboardRiskDistributionAssembler(overviewDisplayMapper),
        new DashboardRuleAssembler(ruleDisplayMapper),
        new DashboardHighRiskReviewAssembler(statusMapper),
        new DashboardLlmQualityStatsAssembler(llmQualityFormatter),
        llmQualityTrendBuilder,
        reviewTrendWindow,
        systemHealthProbe,
        snapshotStore,
        dailySnapshotService
    );

    @BeforeEach
    void setUp() {
        when(dailySnapshotService.selectLlmQualityTrendCounts(any())).thenReturn(List.of());
        when(dailySnapshotService.selectReviewTrendCounts(any())).thenReturn(List.of());
    }

    @Test
    void overviewCacheUsesSynchronizedLoadingToAvoidTtlStampede() throws Exception {
        Cacheable cacheable = DashboardServiceImpl.class
            .getMethod("getOverview", Integer.class)
            .getAnnotation(Cacheable.class);

        assertThat(cacheable).isNotNull();
        assertThat(cacheable.sync()).isTrue();
        assertThat(cacheable.key())
            .isEqualTo("T(com.repoguard.agent.dashboard.DashboardLlmTrendDays).normalize(#llmTrendDays)");
    }

    @Test
    void dashboardModuleMethodsUseSeparatedSynchronizedCaches() throws Exception {
        assertCache("getSummary", CacheNames.DASHBOARD_SUMMARY, "'summary'");
        assertCache("getReviewTrend", CacheNames.DASHBOARD_REVIEW_TREND, "'reviewTrend'");
        assertCache("getRiskDistribution", CacheNames.DASHBOARD_RISK_DISTRIBUTION, "'riskDistribution'");
        assertCache("getRules", CacheNames.DASHBOARD_RULES, "'rules'");
        assertCache("getHighRiskReviews", CacheNames.DASHBOARD_HIGH_RISK_REVIEWS, "'highRiskReviews'");

        Cacheable llmQualityCache = DashboardServiceImpl.class
            .getMethod("getLlmQuality", Integer.class)
            .getAnnotation(Cacheable.class);
        assertThat(llmQualityCache.cacheNames()).containsExactly(CacheNames.DASHBOARD_LLM_QUALITY);
        assertThat(llmQualityCache.sync()).isTrue();
        assertThat(llmQualityCache.key())
            .isEqualTo("T(com.repoguard.agent.dashboard.DashboardLlmTrendDays).normalize(#llmTrendDays)");
    }

    @Test
    void constructorRejectsMissingDashboardMetricAssembler() {
        assertThatThrownBy(() -> new DashboardServiceImpl(
            dashboardMapper,
            null,
            new DashboardReviewTrendAssembler(),
            new DashboardRiskDistributionAssembler(overviewDisplayMapper),
            new DashboardRuleAssembler(ruleDisplayMapper),
            new DashboardHighRiskReviewAssembler(statusMapper),
            new DashboardLlmQualityStatsAssembler(llmQualityFormatter),
            llmQualityTrendBuilder,
            reviewTrendWindow,
            systemHealthProbe,
            snapshotStore,
            dailySnapshotService
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("dashboardMetricAssembler");
    }

    @Test
    void summaryModuleLoadsOnlyMetricAggregate() {
        when(dailySnapshotService.selectMetricStat(any())).thenReturn(metricStat(3L, 2L, 1L, BigDecimal.valueOf(1800)));

        var summary = service.getSummary();

        verify(dailySnapshotService).selectMetricStat(LocalDate.of(2026, 6, 13));
        verify(dailySnapshotService, never()).selectReviewTrendCounts(any());
        verify(dailySnapshotService, never()).selectRuleHitCounts(any());
        verify(dailySnapshotService, never()).selectLlmQualityByModelStats(any());
        verify(dashboardMapper, never()).selectMetricStat(any());
        assertThat(summary).hasSize(4);
        assertThat(summary.get(0).value()).isEqualTo("3");
    }

    @Test
    void summaryModuleReturnsLastSnapshotWhenRefreshFails() {
        when(dailySnapshotService.selectMetricStat(any())).thenReturn(metricStat(3L, 2L, 1L, BigDecimal.valueOf(1800)));
        var firstSummary = service.getSummary();
        when(dailySnapshotService.selectMetricStat(any())).thenThrow(new IllegalStateException("database busy"));

        var secondSummary = service.getSummary();

        assertThat(secondSummary).isEqualTo(firstSummary);
        assertThat(secondSummary.get(0).value()).isEqualTo("3");
    }

    @Test
    void llmQualityModuleLoadsOnlyLlmQualityAggregates() {
        when(dailySnapshotService.selectLlmQualityByModelStats(any())).thenReturn(List.of(
            llmQualityModelStat("dashscope / qwen-plus", 3L, 1733, 1200, "0.000123", 1L, 1L, 1L, 3L, 2L, 1L)
        ));
        when(dailySnapshotService.selectLlmQualityByRepositoryStats(any())).thenReturn(List.of(
            llmQualityRepositoryStat("octocat/api", 3L, 1L, 1L, 3L, 2L, 1L)
        ));
        when(dailySnapshotService.selectLlmQualityTrendCounts(any())).thenReturn(List.of(
            llmQualityTrendCount("2026-06-19", 3L, 2L, 0L, 1L)
        ));

        var llmQuality = service.getLlmQuality(7);

        verify(dailySnapshotService).selectLlmQualityByModelStats(LocalDate.of(2026, 6, 13));
        verify(dailySnapshotService).selectLlmQualityByRepositoryStats(LocalDate.of(2026, 6, 13));
        verify(dailySnapshotService).selectLlmQualityTrendCounts(LocalDate.of(2026, 6, 13));
        verify(dailySnapshotService, never()).selectMetricStat(any());
        verify(dailySnapshotService, never()).selectRiskLevelCounts(any());
        verify(dashboardMapper, never()).selectRecentHighRiskReviews(any());
        assertThat(llmQuality.byModel()).hasSize(1);
        assertThat(llmQuality.byRepository()).hasSize(1);
        assertThat(llmQuality.trend()).hasSize(7);
    }

    @Test
    void systemHealthReportsConfiguredDependenciesAsHealthy() {
        when(rabbitRuntimeHealthProbe.connectionStatus()).thenReturn("CONNECTED");
        when(githubIntegrationProvider.getSettings()).thenReturn(githubSettings("CONFIGURED", "ghp_test"));
        when(reviewPolicyProvider.getSettings()).thenReturn(reviewPolicySettings("sk-test"));

        Map<String, String> health = healthByName(service.getSystemHealth());

        assertThat(health).containsEntry("MySQL", "正常");
        assertThat(health).containsEntry("RabbitMQ", "正常");
        assertThat(health).containsEntry("GitHub", "正常");
        assertThat(health).containsEntry("Spring AI", "正常");
    }

    @Test
    void systemHealthKeepsRenderingWhenDependencyHealthChecksFail() {
        when(rabbitRuntimeHealthProbe.connectionStatus()).thenReturn("DISCONNECTED");
        when(githubIntegrationProvider.getSettings()).thenReturn(githubSettings("FAILED", "ghp_test"));
        when(reviewPolicyProvider.getSettings()).thenReturn(ReviewPolicySettings.empty());

        Map<String, String> health = healthByName(service.getSystemHealth());

        assertThat(health).containsEntry("RabbitMQ", "异常");
        assertThat(health).containsEntry("GitHub", "异常");
        assertThat(health).containsEntry("Spring AI", "未接入");
    }

    @Test
    void overviewDoesNotRunSynchronousDependencyHealthProbe() {
        when(rabbitRuntimeHealthProbe.connectionStatus())
            .thenThrow(new IllegalStateException("RabbitMQ unavailable"));

        var overview = service.getOverview(null);

        assertThat(overview.systemHealth()).isEmpty();
    }

    @Test
    void overviewBuildsTopMetricsFromAggregateQuery() {
        when(dailySnapshotService.selectMetricStat(any())).thenReturn(metricStat(3L, 2L, 1L, BigDecimal.valueOf(1800)));
        when(rabbitTemplate.execute(org.mockito.ArgumentMatchers.<ChannelCallback<Boolean>>any())).thenReturn(true);
        when(githubIntegrationProvider.getSettings()).thenReturn(githubSettings("CONFIGURED", "ghp_test"));
        when(reviewPolicyProvider.getSettings()).thenReturn(reviewPolicySettings("sk-test"));

        var metrics = service.getOverview(null).overviewMetrics();

        verify(dailySnapshotService).selectMetricStat(LocalDate.of(2026, 6, 13));
        verify(dailySnapshotService, never()).selectReviewTrendCounts(any());
        verify(dailySnapshotService, never()).selectRiskLevelCounts(any());
        verify(dailySnapshotService, never()).selectRuleHitCounts(any());
        verify(dashboardMapper, never()).selectRecentHighRiskReviews(any());
        verify(dashboardMapper, never()).selectMetricStat(any());
        verify(dailySnapshotService, never()).selectLlmQualityByModelStats(any());
        verify(dailySnapshotService, never()).selectLlmQualityByRepositoryStats(any());
        verify(dailySnapshotService, never()).selectLlmQualityTrendCounts(any());
        assertThat(metrics).extracting("label").containsExactly("本周审查", "高风险 PR", "失败任务", "平均审查耗时");
        assertThat(metrics).extracting("trendType").containsExactly("up", "up-danger", "down", "down");
        assertThat(metrics).extracting("color").containsExactly("blue", "red", "orange", "green");
        assertThat(metrics.get(0).value()).isEqualTo("3");
        assertThat(metrics.get(1).value()).isEqualTo("2");
        assertThat(metrics.get(1).trend()).isEqualTo("66.7%");
        assertThat(metrics.get(2).value()).isEqualTo("1");
        assertThat(metrics.get(2).trend()).isEqualTo("33.3%");
        assertThat(metrics.get(3).value()).contains("30");
    }

    @Test
    void overviewBuildsReviewTrendFromGroupedQuery() {
        when(dailySnapshotService.selectReviewTrendCounts(any())).thenReturn(List.of(
            reviewTrendCount("06-15", 2L),
            reviewTrendCount("06-16", 3L)
        ));
        when(rabbitTemplate.execute(org.mockito.ArgumentMatchers.<ChannelCallback<Boolean>>any())).thenReturn(true);
        when(githubIntegrationProvider.getSettings()).thenReturn(githubSettings("CONFIGURED", "ghp_test"));
        when(reviewPolicyProvider.getSettings()).thenReturn(reviewPolicySettings("sk-test"));

        var reviewTrend = service.getReviewTrend();

        verify(dailySnapshotService).selectReviewTrendCounts(LocalDate.of(2026, 6, 13));
        assertThat(reviewTrend).hasSize(2);
        assertThat(reviewTrend.get(0).date()).isEqualTo("06-15");
        assertThat(reviewTrend.get(0).value()).isEqualTo(2L);
        assertThat(reviewTrend.get(1).date()).isEqualTo("06-16");
        assertThat(reviewTrend.get(1).value()).isEqualTo(3L);
    }

    @Test
    void overviewFallsBackToLatestReviewWindowWhenCurrentWindowHasNoReviews() {
        when(dailySnapshotService.latestReviewDate()).thenReturn(LocalDate.of(2026, 6, 10));
        when(dailySnapshotService.selectMetricStat(any())).thenReturn(metricStat(4L, 1L, 0L, BigDecimal.valueOf(1200)));
        when(rabbitTemplate.execute(org.mockito.ArgumentMatchers.<ChannelCallback<Boolean>>any())).thenReturn(true);
        when(githubIntegrationProvider.getSettings()).thenReturn(githubSettings("CONFIGURED", "ghp_test"));
        when(reviewPolicyProvider.getSettings()).thenReturn(reviewPolicySettings("sk-test"));

        var overview = service.getOverview(7);

        LocalDate fallbackStartDate = LocalDate.of(2026, 6, 4);
        verify(dailySnapshotService).selectMetricStat(fallbackStartDate);
        verify(dailySnapshotService, never()).selectReviewTrendCounts(any());
        verify(dailySnapshotService, never()).selectRiskLevelCounts(any());
        verify(dailySnapshotService, never()).selectRuleHitCounts(any());
        verify(dashboardMapper, never()).selectRecentHighRiskReviews(any());
        verify(dailySnapshotService, never()).selectLlmQualityByModelStats(any());
        verify(dailySnapshotService, never()).selectLlmQualityByRepositoryStats(any());
        verify(dailySnapshotService, never()).selectLlmQualityTrendCounts(any());
        assertThat(overview.overviewMetrics()).hasSize(4);
        assertThat(overview.reviewTrend()).isEmpty();
        assertThat(overview.llmQualityTrend()).isEmpty();
    }

    @Test
    void overviewBuildsRiskDistributionFromGroupedQuery() {
        when(dailySnapshotService.selectRiskLevelCounts(any())).thenReturn(List.of(
            riskLevelCount("HIGH", 1L),
            riskLevelCount("MEDIUM", 2L),
            riskLevelCount("INFO", 1L)
        ));
        when(rabbitTemplate.execute(org.mockito.ArgumentMatchers.<ChannelCallback<Boolean>>any())).thenReturn(true);
        when(githubIntegrationProvider.getSettings()).thenReturn(githubSettings("CONFIGURED", "ghp_test"));
        when(reviewPolicyProvider.getSettings()).thenReturn(reviewPolicySettings("sk-test"));

        var riskDistribution = service.getRiskDistribution();

        verify(dailySnapshotService).selectRiskLevelCounts(LocalDate.of(2026, 6, 13));
        assertThat(riskDistribution).hasSize(4);
        assertThat(riskDistribution).extracting("name").containsExactly("高风险", "中风险", "低风险", "提示");
        assertThat(riskDistribution).extracting("color").containsExactly("#ef4444", "#f59e0b", "#2563eb", "#22c55e");
        assertThat(riskDistribution.get(0).value()).isEqualTo(1L);
        assertThat(riskDistribution.get(0).percent()).isEqualTo("25.0%");
        assertThat(riskDistribution.get(1).value()).isEqualTo(2L);
        assertThat(riskDistribution.get(1).percent()).isEqualTo("50.0%");
        assertThat(riskDistribution.get(2).value()).isZero();
        assertThat(riskDistribution.get(2).percent()).isEqualTo("0.0%");
        assertThat(riskDistribution.get(3).value()).isEqualTo(1L);
        assertThat(riskDistribution.get(3).percent()).isEqualTo("25.0%");
    }

    @Test
    void overviewBuildsRuleHitsFromGroupedQuery() {
        when(dailySnapshotService.selectRuleHitCounts(any())).thenReturn(List.of(
            ruleHitCount("RG-API-001", 2L),
            ruleHitCount(null, 1L),
            ruleHitCount("RG-SECRET-001", 3L)
        ));
        when(rabbitTemplate.execute(org.mockito.ArgumentMatchers.<ChannelCallback<Boolean>>any())).thenReturn(true);
        when(githubIntegrationProvider.getSettings()).thenReturn(githubSettings("CONFIGURED", "ghp_test"));
        when(reviewPolicyProvider.getSettings()).thenReturn(reviewPolicySettings("sk-test"));

        var overview = service.getRules();

        verify(dailySnapshotService).selectRuleHitCounts(LocalDate.of(2026, 6, 13));
        assertThat(overview.ruleHits()).hasSize(3);
        assertThat(overview.ruleHits().get(0).name()).isEqualTo("\u786c\u7f16\u7801\u5bc6\u94a5\u68c0\u6d4b");
        assertThat(overview.ruleHits().get(0).color()).isEqualTo("#ef4444");
        assertThat(overview.ruleHits().get(0).value()).isEqualTo(3L);
        assertThat(overview.ruleHits().get(0).percent()).isEqualTo("50.0%");
        assertThat(overview.ruleHits().get(1).name()).isEqualTo("Controller \u65e0\u6d4b\u8bd5");
        assertThat(overview.ruleHits().get(1).color()).isEqualTo("#f59e0b");
        assertThat(overview.ruleHits().get(1).value()).isEqualTo(2L);
        assertThat(overview.ruleHits().get(1).percent()).isEqualTo("33.3%");
        assertThat(overview.ruleHits().get(2).name()).isEqualTo("LLM \u5ba1\u67e5");
        assertThat(overview.ruleHits().get(2).color()).isEqualTo("#14b8a6");
        assertThat(overview.ruleHits().get(2).value()).isEqualTo(1L);
        assertThat(overview.ruleHits().get(2).percent()).isEqualTo("16.7%");
        assertThat(overview.failedRules()).extracting("name").containsExactly(
            "\u786c\u7f16\u7801\u5bc6\u94a5\u68c0\u6d4b",
            "Controller \u65e0\u6d4b\u8bd5",
            "LLM \u5ba1\u67e5"
        );
        assertThat(overview.failedRules()).extracting("count").containsExactly(3L, 2L, 1L);
        assertThat(overview.failedRules()).extracting("percent").containsExactly("50.0%", "33.3%", "16.7%");
    }

    @Test
    void overviewBuildsHighRiskReviewsFromLimitedQuery() {
        when(dashboardMapper.selectRecentHighRiskReviews(any())).thenReturn(List.of(
            highRiskReview("Fix auth bypass", "api", "CRITICAL", 4L, "COMPLETED", LocalDateTime.of(2026, 6, 17, 9, 30)),
            highRiskReview("Harden config", "ops", "HIGH", 2L, "FAILED", LocalDateTime.of(2026, 6, 16, 18, 15))
        ));
        when(rabbitTemplate.execute(org.mockito.ArgumentMatchers.<ChannelCallback<Boolean>>any())).thenReturn(true);
        when(githubIntegrationProvider.getSettings()).thenReturn(githubSettings("CONFIGURED", "ghp_test"));
        when(reviewPolicyProvider.getSettings()).thenReturn(reviewPolicySettings("sk-test"));

        var highRiskReviews = service.getHighRiskReviews();

        verify(dashboardMapper).selectRecentHighRiskReviews(LocalDate.of(2026, 6, 13));
        assertThat(highRiskReviews).hasSize(2);
        assertThat(highRiskReviews.get(0).title()).isEqualTo("Fix auth bypass");
        assertThat(highRiskReviews.get(0).riskLevel()).isEqualTo("critical");
        assertThat(highRiskReviews.get(0).ruleHits()).isEqualTo(4L);
        assertThat(highRiskReviews.get(0).reviewedAt()).isEqualTo("2026-06-17 09:30");
        assertThat(highRiskReviews.get(1).title()).isEqualTo("Harden config");
        assertThat(highRiskReviews.get(1).riskLevel()).isEqualTo("high");
        assertThat(highRiskReviews.get(1).ruleHits()).isEqualTo(2L);
        assertThat(highRiskReviews.get(1).status()).isEqualTo("失败");
    }

    @Test
    void overviewBuildsLlmQualityTrendFromGroupedQuery() {
        String yesterdayKey = LocalDate.of(2026, 6, 18).toString();
        String todayKey = LocalDate.of(2026, 6, 19).toString();
        when(dailySnapshotService.selectLlmQualityTrendCounts(any())).thenReturn(List.of(
            llmQualityTrendCount(yesterdayKey, 2L, 1L, 1L, 0L),
            llmQualityTrendCount(todayKey, 3L, 2L, 0L, 1L)
        ));
        when(rabbitTemplate.execute(org.mockito.ArgumentMatchers.<ChannelCallback<Boolean>>any())).thenReturn(true);
        when(githubIntegrationProvider.getSettings()).thenReturn(githubSettings("CONFIGURED", "ghp_test"));
        when(reviewPolicyProvider.getSettings()).thenReturn(reviewPolicySettings("sk-test"));

        var trend = service.getLlmQuality(7).trend();

        verify(dailySnapshotService).selectLlmQualityTrendCounts(LocalDate.of(2026, 6, 13));
        assertThat(trend).hasSize(7);
        assertThat(trend.get(4).taskCount()).isZero();
        assertThat(trend.get(4).parseSuccessRate()).isEqualTo("0.0%");
        assertThat(trend.get(5).taskCount()).isEqualTo(2L);
        assertThat(trend.get(5).parseSuccessRate()).isEqualTo("50.0%");
        assertThat(trend.get(5).fallbackRate()).isEqualTo("50.0%");
        assertThat(trend.get(6).taskCount()).isEqualTo(3L);
        assertThat(trend.get(6).parseSuccessRate()).isEqualTo("66.7%");
        assertThat(trend.get(6).partialFallbackRate()).isEqualTo("33.3%");
    }

    @Test
    void overviewReportsLlmQualityByModelAndRepository() {
        when(dailySnapshotService.selectLlmQualityByModelStats(any())).thenReturn(List.of(
            llmQualityModelStat("dashscope / qwen-plus", 3L, 1733, 1200, "0.000123", 1L, 1L, 1L, 3L, 2L, 1L),
            llmQualityModelStat("openai / gpt-test", 1L, 800, 900, "0.000456", 1L, 0L, 0L, 0L, 0L, 0L)
        ));
        when(dailySnapshotService.selectLlmQualityByRepositoryStats(any())).thenReturn(List.of(
            llmQualityRepositoryStat("octocat/api", 3L, 1L, 1L, 3L, 2L, 1L),
            llmQualityRepositoryStat("octocat/web", 1L, 0L, 0L, 0L, 0L, 0L)
        ));
        when(rabbitTemplate.execute(org.mockito.ArgumentMatchers.<ChannelCallback<Boolean>>any())).thenReturn(true);
        when(githubIntegrationProvider.getSettings()).thenReturn(githubSettings("CONFIGURED", "ghp_test"));
        when(reviewPolicyProvider.getSettings()).thenReturn(reviewPolicySettings("sk-test"));

        var overview = service.getLlmQuality(30);

        verify(dailySnapshotService).selectLlmQualityByModelStats(LocalDate.of(2026, 6, 13));
        verify(dailySnapshotService).selectLlmQualityByRepositoryStats(LocalDate.of(2026, 6, 13));
        assertThat(overview.byModel()).hasSize(2);
        var qwen = overview.byModel().stream()
            .filter(item -> "dashscope / qwen-plus".equals(item.model()))
            .findFirst()
            .orElseThrow();
        assertThat(qwen.taskCount()).isEqualTo(3);
        assertThat(qwen.averageDuration()).isEqualTo("1.7 s");
        assertThat(qwen.averageTokens()).isEqualTo("1200");
        assertThat(qwen.averageCost()).isEqualTo("$0.000123");
        assertThat(qwen.parseSuccessRate()).isEqualTo("33.3%");
        assertThat(qwen.fallbackRate()).isEqualTo("33.3%");
        assertThat(qwen.partialFallbackRate()).isEqualTo("33.3%");
        assertThat(qwen.validRate()).isEqualTo("66.7%");
        assertThat(qwen.falsePositiveRate()).isEqualTo("33.3%");

        var api = overview.byRepository().stream()
            .filter(item -> "octocat/api".equals(item.repository()))
            .findFirst()
            .orElseThrow();
        assertThat(api.taskCount()).isEqualTo(3);
        assertThat(api.fallbackRate()).isEqualTo("33.3%");
        assertThat(api.partialFallbackRate()).isEqualTo("33.3%");
        assertThat(api.validRate()).isEqualTo("66.7%");
        assertThat(overview.trend()).hasSize(30);
    }

    private Map<String, String> healthByName(List<SystemHealthItemDto> healthItems) {
        return healthItems.stream().collect(Collectors.toMap(SystemHealthItemDto::name, SystemHealthItemDto::status));
    }

    private void assertCache(String methodName, String cacheName, String key) throws NoSuchMethodException {
        Cacheable cacheable = DashboardServiceImpl.class
            .getMethod(methodName)
            .getAnnotation(Cacheable.class);
        assertThat(cacheable.cacheNames()).containsExactly(cacheName);
        assertThat(cacheable.sync()).isTrue();
        assertThat(cacheable.key()).isEqualTo(key);
    }

    private DashboardRiskLevelCount riskLevelCount(String riskLevel, Long total) {
        DashboardRiskLevelCount count = new DashboardRiskLevelCount();
        count.setRiskLevel(riskLevel);
        count.setTotal(total);
        return count;
    }

    private DashboardReviewTrendCount reviewTrendCount(String dayLabel, Long total) {
        DashboardReviewTrendCount count = new DashboardReviewTrendCount();
        count.setDayLabel(dayLabel);
        count.setTotal(total);
        return count;
    }

    private DashboardRuleHitCount ruleHitCount(String ruleId, Long total) {
        DashboardRuleHitCount count = new DashboardRuleHitCount();
        count.setRuleId(ruleId);
        count.setTotal(total);
        return count;
    }

    private DashboardMetricStat metricStat(Long total, Long highRisk, Long failed, BigDecimal averageDurationSeconds) {
        DashboardMetricStat stat = new DashboardMetricStat();
        stat.setTotal(total);
        stat.setHighRisk(highRisk);
        stat.setFailed(failed);
        stat.setAverageDurationSeconds(averageDurationSeconds);
        return stat;
    }

    private DashboardHighRiskReview highRiskReview(
        String title,
        String repository,
        String riskLevel,
        Long ruleHits,
        String status,
        LocalDateTime createdAt
    ) {
        DashboardHighRiskReview review = new DashboardHighRiskReview();
        review.setTitle(title);
        review.setRepository(repository);
        review.setRiskLevel(riskLevel);
        review.setRuleHits(ruleHits);
        review.setStatus(status);
        review.setCreatedAt(createdAt);
        return review;
    }

    private DashboardLlmQualityTrendCount llmQualityTrendCount(
        String dayKey,
        Long taskCount,
        Long parseSuccessCount,
        Long fallbackCount,
        Long partialFallbackCount
    ) {
        DashboardLlmQualityTrendCount count = new DashboardLlmQualityTrendCount();
        count.setDayKey(dayKey);
        count.setTaskCount(taskCount);
        count.setParseSuccessCount(parseSuccessCount);
        count.setFallbackCount(fallbackCount);
        count.setPartialFallbackCount(partialFallbackCount);
        return count;
    }

    private DashboardLlmQualityModelStat llmQualityModelStat(
        String modelLabel,
        Long taskCount,
        Integer averageDurationMs,
        Integer averageTokens,
        String averageCost,
        Long parseSuccessCount,
        Long fallbackCount,
        Long partialFallbackCount,
        Long reviewedFeedbackCount,
        Long validFeedbackCount,
        Long falsePositiveFeedbackCount
    ) {
        DashboardLlmQualityModelStat stat = new DashboardLlmQualityModelStat();
        stat.setModelLabel(modelLabel);
        stat.setTaskCount(taskCount);
        stat.setAverageDurationMs(BigDecimal.valueOf(averageDurationMs));
        stat.setAverageTokens(BigDecimal.valueOf(averageTokens));
        stat.setAverageCost(new BigDecimal(averageCost));
        stat.setParseSuccessCount(parseSuccessCount);
        stat.setFallbackCount(fallbackCount);
        stat.setPartialFallbackCount(partialFallbackCount);
        stat.setReviewedFeedbackCount(reviewedFeedbackCount);
        stat.setValidFeedbackCount(validFeedbackCount);
        stat.setFalsePositiveFeedbackCount(falsePositiveFeedbackCount);
        return stat;
    }

    private DashboardLlmQualityRepositoryStat llmQualityRepositoryStat(
        String repositoryLabel,
        Long taskCount,
        Long fallbackCount,
        Long partialFallbackCount,
        Long reviewedFeedbackCount,
        Long validFeedbackCount,
        Long falsePositiveFeedbackCount
    ) {
        DashboardLlmQualityRepositoryStat stat = new DashboardLlmQualityRepositoryStat();
        stat.setRepositoryLabel(repositoryLabel);
        stat.setTaskCount(taskCount);
        stat.setFallbackCount(fallbackCount);
        stat.setPartialFallbackCount(partialFallbackCount);
        stat.setReviewedFeedbackCount(reviewedFeedbackCount);
        stat.setValidFeedbackCount(validFeedbackCount);
        stat.setFalsePositiveFeedbackCount(falsePositiveFeedbackCount);
        return stat;
    }

    private GithubIntegrationSettings githubSettings(String status, String token) {
        return new GithubIntegrationSettings("GITHUB", status, "https://api.github.com", token, null, "octocat", "api", 1L);
    }

    private ReviewPolicySettings reviewPolicySettings(String apiKey) {
        return new ReviewPolicySettings(
            true,
            true,
            "dashscope",
            "qwen-plus",
            "https://dashscope.aliyuncs.com/compatible-mode/v1",
            apiKey,
            60,
            BigDecimal.valueOf(0.20),
            4096,
            true,
            1,
            6,
            700,
            4,
            450,
            BigDecimal.valueOf(0.5),
            BigDecimal.valueOf(1.5)
        );
    }
}
