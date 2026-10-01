package com.repoguard.agent.notification.center;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.repoguard.agent.github.GithubIntegrationProvider;
import com.repoguard.agent.github.GithubIntegrationSettings;
import com.repoguard.agent.review.ReviewPolicyProvider;
import com.repoguard.agent.review.ReviewPolicySettings;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.messaging.RabbitRuntimeHealthProbe;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NotificationServiceImplTest {

    private final ReviewTaskMapper reviewTaskMapper = org.mockito.Mockito.mock(ReviewTaskMapper.class);
    private final GithubIntegrationProvider githubIntegrationProvider = org.mockito.Mockito.mock(GithubIntegrationProvider.class);
    private final ReviewPolicyProvider reviewPolicyProvider = org.mockito.Mockito.mock(ReviewPolicyProvider.class);
    private final RabbitRuntimeHealthProbe rabbitRuntimeHealthProbe =
        org.mockito.Mockito.mock(RabbitRuntimeHealthProbe.class);
    private final NotificationServiceImpl service = new NotificationServiceImpl(
        reviewTaskMapper,
        githubIntegrationProvider,
        reviewPolicyProvider,
        rabbitRuntimeHealthProbe
    );

    @BeforeEach
    void initializeLambdaMetadata() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
            new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
            ReviewTask.class);
    }

    @Test
    void getNotificationsBuildsItemsFromTasksAndIntegrationStatus() {
        when(reviewTaskMapper.selectList(any())).thenReturn(List.of())
            .thenReturn(List.of(task(1L, "FAILED", "HIGH", "FAILED")))
            .thenReturn(List.of(task(3L, "COMPLETED", "HIGH", "COMPLETED")))
            .thenReturn(List.of(task(2L, "COMPLETED", "MEDIUM", "FALLBACK"), task(4L, "COMPLETED", "HIGH", "FALLBACK")));
        when(githubIntegrationProvider.getSettings()).thenReturn(githubSettings("FAILED", "ghp_test", "bad token"));
        when(rabbitRuntimeHealthProbe.connectionStatus()).thenReturn("DISCONNECTED");
        when(reviewPolicyProvider.getSettings()).thenReturn(reviewPolicySettings(""));

        var result = service.getNotifications();

        assertThat(result.total()).isGreaterThanOrEqualTo(5);
        assertThat(result.items()).extracting("id").contains(
            "review-failed-1",
            "review-high-risk-3",
            "review-llm-fallback-2",
            "integration-github-failed",
            "integration-rabbitmq-failed",
            "llm-missing-secret"
        );
        assertThat(result.items()).extracting("id")
            .doesNotContain("review-high-risk-1", "review-high-risk-4");
        assertThat(result.items().getFirst().targetPath()).startsWith("/repoguard/");
    }

    @Test
    void queriesEachUnresolvedCategoryBeforeItsLimitAndProjectsOnlyNotificationFields() {
        healthyIntegrations();
        when(reviewTaskMapper.selectList(any())).thenReturn(List.of());
        assertThat(service.getNotifications().total()).isZero();
        var queries = org.mockito.ArgumentCaptor.<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ReviewTask>>captor();
        org.mockito.Mockito.verify(reviewTaskMapper, org.mockito.Mockito.times(4)).selectList(queries.capture());
        var wrappers = queries.getAllValues();
        assertThat(wrappers.getFirst().getSqlSegment()).contains("human_review_status", "review_sla_deadline <=",
            "ORDER BY review_sla_deadline ASC,id ASC", "limit 4");
        assertThat(wrappers.get(1).getSqlSegment()).contains("status =", "ORDER BY created_at DESC,id DESC", "limit 4");
        assertThat(wrappers.get(1).getParamNameValuePairs().values()).contains("FAILED");
        assertThat(wrappers.get(2).getSqlSegment()).contains("assessment_status =", "risk_level IN", "human_reviewed_at IS NULL", "limit 4");
        assertThat(wrappers.get(2).getParamNameValuePairs().values()).contains("COMPLETE", "HIGH", "CRITICAL", "PENDING", "NOT_REQUIRED");
        assertThat(wrappers.get(3).getSqlSegment()).contains("llm_status =", "human_reviewed_at IS NULL", "limit 3");
        assertThat(wrappers.get(3).getParamNameValuePairs().values()).contains("FALLBACK", "COMPLETED", "PENDING_HUMAN_REVIEW");
        assertThat(wrappers).allSatisfy(wrapper -> assertThat(wrapper.getSqlSelect())
            .contains("id", "title", "review_sla_deadline")
            .doesNotContain("llm_prompt_summary", "human_review_note", "last_publish_error", "*"));
    }

    @Test
    void reservesOldOverdueAndSystemNotificationsAndGivesOtherCategoriesDisplaySlots() {
        var overdue = java.util.stream.LongStream.rangeClosed(1, 4).mapToObj(id -> {
            ReviewTask item = task(id, "PENDING_HUMAN_REVIEW", "HIGH", "COMPLETED");
            item.setCreatedAt(LocalDateTime.now().minusDays(30));
            item.setReviewSlaDeadline(LocalDateTime.now().minusDays(2));
            return item;
        }).toList();
        var failed = java.util.stream.LongStream.rangeClosed(10, 13).mapToObj(id -> task(id, "FAILED", "LOW", "FAILED")).toList();
        var highRisk = java.util.stream.LongStream.rangeClosed(20, 23).mapToObj(id -> task(id, "COMPLETED", "HIGH", "COMPLETED")).toList();
        var fallback = java.util.stream.LongStream.rangeClosed(30, 32).mapToObj(id -> task(id, "COMPLETED", "LOW", "FALLBACK")).toList();
        when(reviewTaskMapper.selectList(any())).thenReturn(overdue).thenReturn(failed).thenReturn(highRisk).thenReturn(fallback);
        when(githubIntegrationProvider.getSettings()).thenReturn(githubSettings("FAILED", "fixture-token", "fixture failure"));
        when(rabbitRuntimeHealthProbe.connectionStatus()).thenReturn("DISCONNECTED");
        when(reviewPolicyProvider.getSettings()).thenReturn(reviewPolicySettings(""));

        var result = service.getNotifications();
        assertThat(result.total()).isEqualTo(12);
        assertThat(result.items()).hasSize(12);
        assertThat(result.items().subList(0, 4)).extracting("id").containsExactly(
            "review-sla-overdue-1", "review-sla-overdue-2", "review-sla-overdue-3", "review-sla-overdue-4");
        assertThat(result.items()).extracting("id").contains("integration-github-failed", "integration-rabbitmq-failed",
            "llm-missing-secret", "review-failed-10", "review-high-risk-20", "review-llm-fallback-30");
    }

    @Test
    void deduplicatesTaskRemindersAndExcludesReservedTasksBeforeLowerPriorityLimits() {
        healthyIntegrations();
        ReviewTask overdue = task(1L, "PENDING_HUMAN_REVIEW", "HIGH", "COMPLETED");
        overdue.setReviewSlaDeadline(LocalDateTime.now().minusHours(1));
        ReviewTask highRisk = task(2L, "COMPLETED", "HIGH", "COMPLETED");
        ReviewTask fallback = task(3L, "COMPLETED", "LOW", "FALLBACK");
        when(reviewTaskMapper.selectList(any())).thenReturn(List.of(overdue)).thenReturn(List.of())
            .thenReturn(List.of(overdue, highRisk)).thenReturn(List.of(highRisk, fallback));
        var result = service.getNotifications();
        assertThat(result.items()).extracting("id").containsExactly("review-sla-overdue-1", "review-high-risk-2", "review-llm-fallback-3");
        var queries = org.mockito.ArgumentCaptor.<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ReviewTask>>captor();
        org.mockito.Mockito.verify(reviewTaskMapper, org.mockito.Mockito.times(4)).selectList(queries.capture());
        assertThat(queries.getAllValues().get(2).getSqlSegment()).contains("id NOT IN", "limit 4");
        assertThat(queries.getAllValues().get(2).getParamNameValuePairs().values()).contains(1L);
        assertThat(queries.getAllValues().get(3).getSqlSegment()).contains("id NOT IN", "limit 3");
        assertThat(queries.getAllValues().get(3).getParamNameValuePairs().values()).contains(1L, 2L);
    }

    @Test
    void treatsNullCandidateResultsAsEmpty() {
        healthyIntegrations();
        when(reviewTaskMapper.selectList(any())).thenReturn(null);
        assertThat(service.getNotifications().items()).isEmpty();
    }

    private void healthyIntegrations() {
        when(githubIntegrationProvider.getSettings()).thenReturn(githubSettings("CONNECTED", "fixture-token", ""));
        when(rabbitRuntimeHealthProbe.connectionStatus()).thenReturn("CONNECTED");
        when(reviewPolicyProvider.getSettings()).thenReturn(reviewPolicySettings("fixture-key"));
    }

    private ReviewTask task(Long id, String status, String riskLevel, String llmStatus) {
        ReviewTask task = new ReviewTask();
        task.setId(id);
        task.setPrNumber(id.intValue() + 10);
        task.setTitle("Notification smoke " + id);
        task.setRepository("PRAgent");
        task.setOrganization("cocojiu");
        task.setCommitSha("abc" + id);
        task.setBranchName("PRAgent-test");
        task.setStatus(status);
        task.setHumanReviewStatus("PENDING_HUMAN_REVIEW".equals(status) ? "PENDING" : "NOT_REQUIRED");
        task.setRiskLevel(riskLevel);
        task.setAssessmentStatus(
            "FAILED".equals(status)
                ? "FAILED"
                : "FALLBACK".equals(llmStatus) ? "PARTIAL" : "COMPLETE"
        );
        task.setLlmStatus(llmStatus);
        task.setMqRetries(0);
        task.setCreatedAt(LocalDateTime.now().minusMinutes(id));
        task.setDurationSeconds(30);
        return task;
    }

    private GithubIntegrationSettings githubSettings(String status, String token, String lastError) {
        return new GithubIntegrationSettings("GITHUB", status, "https://api.github.com", token, lastError, "octocat", "api", 1L);
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
