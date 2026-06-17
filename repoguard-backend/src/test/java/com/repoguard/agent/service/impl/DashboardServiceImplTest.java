package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.repoguard.agent.config.GithubIntegrationProvider;
import com.repoguard.agent.config.GithubIntegrationSettings;
import com.repoguard.agent.config.ReviewPolicyProvider;
import com.repoguard.agent.config.ReviewPolicySettings;
import com.repoguard.agent.dto.DashboardHighRiskReview;
import com.repoguard.agent.dto.DashboardLlmQualityModelStat;
import com.repoguard.agent.dto.DashboardLlmQualityRepositoryStat;
import com.repoguard.agent.dto.DashboardLlmQualityTrendCount;
import com.repoguard.agent.dto.DashboardReviewTrendCount;
import com.repoguard.agent.dto.DashboardRiskLevelCount;
import com.repoguard.agent.dto.DashboardRuleHitCount;
import com.repoguard.agent.dto.SystemHealthItemDto;
import com.repoguard.agent.entity.ReviewFinding;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.ReviewFindingMapper;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.ChannelCallback;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class DashboardServiceImplTest {

    private final ReviewTaskMapper reviewTaskMapper = org.mockito.Mockito.mock(ReviewTaskMapper.class);
    private final ReviewFindingMapper reviewFindingMapper = org.mockito.Mockito.mock(ReviewFindingMapper.class);
    private final GithubIntegrationProvider githubIntegrationProvider = org.mockito.Mockito.mock(GithubIntegrationProvider.class);
    private final ReviewPolicyProvider reviewPolicyProvider = org.mockito.Mockito.mock(ReviewPolicyProvider.class);
    private final RabbitTemplate rabbitTemplate = org.mockito.Mockito.mock(RabbitTemplate.class);
    private final DashboardServiceImpl service = new DashboardServiceImpl(
        reviewTaskMapper,
        reviewFindingMapper,
        githubIntegrationProvider,
        reviewPolicyProvider,
        rabbitTemplate
    );

    @Test
    void overviewReportsConfiguredDependenciesAsHealthy() {
        when(reviewTaskMapper.selectList(any())).thenReturn(List.of());
        when(reviewFindingMapper.selectList(any())).thenReturn(List.of());
        when(rabbitTemplate.execute(org.mockito.ArgumentMatchers.<ChannelCallback<Boolean>>any())).thenReturn(true);
        when(githubIntegrationProvider.getSettings()).thenReturn(githubSettings("CONFIGURED", "ghp_test"));
        when(reviewPolicyProvider.getSettings()).thenReturn(reviewPolicySettings("sk-test"));

        Map<String, String> health = healthByName(service.getOverview(null).systemHealth());

        assertThat(health).containsEntry("MySQL", "正常");
        assertThat(health).containsEntry("RabbitMQ", "正常");
        assertThat(health).containsEntry("GitHub", "正常");
        assertThat(health).containsEntry("Spring AI", "正常");
    }

    @Test
    void overviewKeepsRenderingWhenDependencyHealthChecksFail() {
        when(reviewTaskMapper.selectList(any())).thenReturn(List.of());
        when(reviewFindingMapper.selectList(any())).thenReturn(List.of());
        when(rabbitTemplate.execute(org.mockito.ArgumentMatchers.<ChannelCallback<Boolean>>any()))
            .thenThrow(new IllegalStateException("RabbitMQ unavailable"));
        when(githubIntegrationProvider.getSettings()).thenReturn(githubSettings("FAILED", "ghp_test"));
        when(reviewPolicyProvider.getSettings()).thenReturn(ReviewPolicySettings.empty());

        Map<String, String> health = healthByName(service.getOverview(null).systemHealth());

        assertThat(health).containsEntry("RabbitMQ", "异常");
        assertThat(health).containsEntry("GitHub", "异常");
        assertThat(health).containsEntry("Spring AI", "未接入");
    }

    @Test
    void overviewBuildsTopMetricsFromCountQueries() {
        when(reviewTaskMapper.selectCount(any())).thenReturn(3L, 2L, 1L);
        when(reviewTaskMapper.selectAverageDurationSeconds()).thenReturn(BigDecimal.valueOf(1800));
        when(reviewFindingMapper.selectList(any())).thenReturn(List.of());
        when(rabbitTemplate.execute(org.mockito.ArgumentMatchers.<ChannelCallback<Boolean>>any())).thenReturn(true);
        when(githubIntegrationProvider.getSettings()).thenReturn(githubSettings("CONFIGURED", "ghp_test"));
        when(reviewPolicyProvider.getSettings()).thenReturn(reviewPolicySettings("sk-test"));

        var metrics = service.getOverview(null).overviewMetrics();

        assertThat(metrics).extracting("label").containsExactly("本周审查", "高风险 PR", "失败任务", "平均审查耗时");
        assertThat(metrics.get(0).value()).isEqualTo("3");
        assertThat(metrics.get(1).value()).isEqualTo("2");
        assertThat(metrics.get(1).trend()).isEqualTo("66.7%");
        assertThat(metrics.get(2).value()).isEqualTo("1");
        assertThat(metrics.get(2).trend()).isEqualTo("33.3%");
        assertThat(metrics.get(3).value()).contains("30");
    }

    @Test
    void overviewBuildsReviewTrendFromGroupedQuery() {
        when(reviewTaskMapper.selectList(any())).thenReturn(List.of());
        when(reviewTaskMapper.selectCount(any())).thenReturn(5L, 0L, 0L);
        when(reviewTaskMapper.selectReviewTrendCounts()).thenReturn(List.of(
            reviewTrendCount("06-15", 2L),
            reviewTrendCount("06-16", 3L)
        ));
        when(reviewFindingMapper.selectList(any())).thenReturn(List.of());
        when(rabbitTemplate.execute(org.mockito.ArgumentMatchers.<ChannelCallback<Boolean>>any())).thenReturn(true);
        when(githubIntegrationProvider.getSettings()).thenReturn(githubSettings("CONFIGURED", "ghp_test"));
        when(reviewPolicyProvider.getSettings()).thenReturn(reviewPolicySettings("sk-test"));

        var reviewTrend = service.getOverview(null).reviewTrend();

        assertThat(reviewTrend).hasSize(2);
        assertThat(reviewTrend.get(0).date()).isEqualTo("06-15");
        assertThat(reviewTrend.get(0).value()).isEqualTo(2L);
        assertThat(reviewTrend.get(1).date()).isEqualTo("06-16");
        assertThat(reviewTrend.get(1).value()).isEqualTo(3L);
    }

    @Test
    void overviewBuildsRiskDistributionFromGroupedQuery() {
        when(reviewTaskMapper.selectList(any())).thenReturn(List.of());
        when(reviewTaskMapper.selectCount(any())).thenReturn(4L, 1L, 0L);
        when(reviewTaskMapper.selectRiskLevelCounts()).thenReturn(List.of(
            riskLevelCount("HIGH", 1L),
            riskLevelCount("MEDIUM", 2L),
            riskLevelCount("INFO", 1L)
        ));
        when(reviewFindingMapper.selectList(any())).thenReturn(List.of());
        when(rabbitTemplate.execute(org.mockito.ArgumentMatchers.<ChannelCallback<Boolean>>any())).thenReturn(true);
        when(githubIntegrationProvider.getSettings()).thenReturn(githubSettings("CONFIGURED", "ghp_test"));
        when(reviewPolicyProvider.getSettings()).thenReturn(reviewPolicySettings("sk-test"));

        var riskDistribution = service.getOverview(null).riskDistribution();

        assertThat(riskDistribution).hasSize(4);
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
        when(reviewTaskMapper.selectList(any())).thenReturn(List.of());
        when(reviewTaskMapper.selectCount(any())).thenReturn(6L, 0L, 0L);
        when(reviewFindingMapper.selectList(any())).thenReturn(List.of());
        when(reviewFindingMapper.selectRuleHitCounts()).thenReturn(List.of(
            ruleHitCount("RG-API-001", 2L),
            ruleHitCount(null, 1L),
            ruleHitCount("RG-SECRET-001", 3L)
        ));
        when(rabbitTemplate.execute(org.mockito.ArgumentMatchers.<ChannelCallback<Boolean>>any())).thenReturn(true);
        when(githubIntegrationProvider.getSettings()).thenReturn(githubSettings("CONFIGURED", "ghp_test"));
        when(reviewPolicyProvider.getSettings()).thenReturn(reviewPolicySettings("sk-test"));

        var overview = service.getOverview(null);

        assertThat(overview.ruleHits()).hasSize(3);
        assertThat(overview.ruleHits().get(0).value()).isEqualTo(3L);
        assertThat(overview.ruleHits().get(0).percent()).isEqualTo("50.0%");
        assertThat(overview.ruleHits().get(1).value()).isEqualTo(2L);
        assertThat(overview.ruleHits().get(1).percent()).isEqualTo("33.3%");
        assertThat(overview.ruleHits().get(2).value()).isEqualTo(1L);
        assertThat(overview.ruleHits().get(2).percent()).isEqualTo("16.7%");
        assertThat(overview.failedRules()).extracting("count").containsExactly(3L, 2L, 1L);
        assertThat(overview.failedRules()).extracting("percent").containsExactly("50.0%", "33.3%", "16.7%");
    }

    @Test
    void overviewBuildsHighRiskReviewsFromLimitedQuery() {
        when(reviewTaskMapper.selectList(any())).thenReturn(List.of());
        when(reviewTaskMapper.selectCount(any())).thenReturn(2L, 2L, 0L);
        when(reviewTaskMapper.selectRecentHighRiskReviews()).thenReturn(List.of(
            highRiskReview("Fix auth bypass", "api", "CRITICAL", 4L, "COMPLETED", LocalDateTime.of(2026, 6, 17, 9, 30)),
            highRiskReview("Harden config", "ops", "HIGH", 2L, "FAILED", LocalDateTime.of(2026, 6, 16, 18, 15))
        ));
        when(reviewFindingMapper.selectList(any())).thenReturn(List.of());
        when(rabbitTemplate.execute(org.mockito.ArgumentMatchers.<ChannelCallback<Boolean>>any())).thenReturn(true);
        when(githubIntegrationProvider.getSettings()).thenReturn(githubSettings("CONFIGURED", "ghp_test"));
        when(reviewPolicyProvider.getSettings()).thenReturn(reviewPolicySettings("sk-test"));

        var highRiskReviews = service.getOverview(null).highRiskReviews();

        assertThat(highRiskReviews).hasSize(2);
        assertThat(highRiskReviews.get(0).title()).isEqualTo("Fix auth bypass");
        assertThat(highRiskReviews.get(0).riskLevel()).isEqualTo("critical");
        assertThat(highRiskReviews.get(0).ruleHits()).isEqualTo(4L);
        assertThat(highRiskReviews.get(0).reviewedAt()).isEqualTo("2026-06-17 09:30");
        assertThat(highRiskReviews.get(1).title()).isEqualTo("Harden config");
        assertThat(highRiskReviews.get(1).riskLevel()).isEqualTo("high");
        assertThat(highRiskReviews.get(1).ruleHits()).isEqualTo(2L);
    }

    @Test
    void overviewBuildsLlmQualityTrendFromGroupedQuery() {
        LocalDateTime today = LocalDateTime.now();
        String yesterdayKey = today.minusDays(1).toLocalDate().toString();
        String todayKey = today.toLocalDate().toString();
        when(reviewTaskMapper.selectList(any())).thenReturn(List.of());
        when(reviewTaskMapper.selectCount(any())).thenReturn(5L, 0L, 0L);
        when(reviewTaskMapper.selectLlmQualityTrendCounts(any())).thenReturn(List.of(
            llmQualityTrendCount(yesterdayKey, 2L, 1L, 1L, 0L),
            llmQualityTrendCount(todayKey, 3L, 2L, 0L, 1L)
        ));
        when(reviewFindingMapper.selectList(any())).thenReturn(List.of());
        when(rabbitTemplate.execute(org.mockito.ArgumentMatchers.<ChannelCallback<Boolean>>any())).thenReturn(true);
        when(githubIntegrationProvider.getSettings()).thenReturn(githubSettings("CONFIGURED", "ghp_test"));
        when(reviewPolicyProvider.getSettings()).thenReturn(reviewPolicySettings("sk-test"));

        var trend = service.getOverview(7).llmQualityTrend();

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
        when(reviewTaskMapper.selectList(any())).thenReturn(List.of(
            task(1L, "octocat", "api", "dashscope", "qwen-plus", "COMPLETED", "PARSED", 1200),
            task(2L, "octocat", "api", "dashscope", "qwen-plus", "FALLBACK", "FALLBACK", 2200),
            task(3L, "octocat", "web", "openai", "gpt-test", "COMPLETED", "PARSED", 800),
            task(4L, "octocat", "api", "dashscope", "qwen-plus", "COMPLETED", "PARTIAL_FALLBACK", 1800)
        ));
        when(reviewTaskMapper.selectLlmQualityByModelStats()).thenReturn(List.of(
            llmQualityModelStat("dashscope / qwen-plus", 3L, 1733, 1200, "0.000123", 1L, 1L, 1L, 3L, 2L, 1L),
            llmQualityModelStat("openai / gpt-test", 1L, 800, 900, "0.000456", 1L, 0L, 0L, 0L, 0L, 0L)
        ));
        when(reviewTaskMapper.selectLlmQualityByRepositoryStats()).thenReturn(List.of(
            llmQualityRepositoryStat("octocat/api", 3L, 1L, 1L, 3L, 2L, 1L),
            llmQualityRepositoryStat("octocat/web", 1L, 0L, 0L, 0L, 0L, 0L)
        ));
        when(reviewFindingMapper.selectList(any())).thenReturn(List.of(
            finding(1L, "VALID"),
            finding(1L, "FALSE_POSITIVE"),
            finding(2L, "VALID"),
            finding(3L, "UNREVIEWED")
        ));
        when(rabbitTemplate.execute(org.mockito.ArgumentMatchers.<ChannelCallback<Boolean>>any())).thenReturn(true);
        when(githubIntegrationProvider.getSettings()).thenReturn(githubSettings("CONFIGURED", "ghp_test"));
        when(reviewPolicyProvider.getSettings()).thenReturn(reviewPolicySettings("sk-test"));

        var overview = service.getOverview(30);

        assertThat(overview.llmQualityByModel()).hasSize(2);
        var qwen = overview.llmQualityByModel().stream()
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

        var api = overview.llmQualityByRepository().stream()
            .filter(item -> "octocat/api".equals(item.repository()))
            .findFirst()
            .orElseThrow();
        assertThat(api.taskCount()).isEqualTo(3);
        assertThat(api.fallbackRate()).isEqualTo("33.3%");
        assertThat(api.partialFallbackRate()).isEqualTo("33.3%");
        assertThat(api.validRate()).isEqualTo("66.7%");
        assertThat(overview.llmQualityTrend()).hasSize(30);
    }

    private Map<String, String> healthByName(List<SystemHealthItemDto> healthItems) {
        return healthItems.stream().collect(Collectors.toMap(SystemHealthItemDto::name, SystemHealthItemDto::status));
    }

    private ReviewTask task(
        Long id,
        String organization,
        String repository,
        String provider,
        String model,
        String llmStatus,
        String parseStatus,
        Integer durationMs
    ) {
        ReviewTask task = new ReviewTask();
        task.setId(id);
        task.setOrganization(organization);
        task.setRepository(repository);
        task.setCreatedAt(LocalDateTime.now().minusDays(id));
        task.setStatus("COMPLETED");
        task.setRiskLevel("LOW");
        task.setLlmStatus(llmStatus);
        task.setLlmParseStatus(parseStatus);
        task.setLlmProvider(provider);
        task.setLlmModel(model);
        task.setLlmDurationMs(durationMs);
        return task;
    }

    private ReviewFinding finding(Long taskId, String feedbackStatus) {
        ReviewFinding finding = new ReviewFinding();
        finding.setTaskId(taskId);
        finding.setCategory("FINDING");
        finding.setFeedbackStatus(feedbackStatus);
        return finding;
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
