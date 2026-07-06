package com.repoguard.agent.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.external.ExternalCallException;
import java.time.Duration;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;

class RepoGuardMetricsTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final RepoGuardMetrics metrics = new RepoGuardMetrics(meterRegistry);

    @Test
    void recordsReviewTaskCountersWithStableTags() {
        metrics.reviewTaskCreated("MANUAL_INPUT");
        metrics.reviewTaskCompleted("HIGH", "FALLBACK");
        metrics.reviewTaskFailed(new IllegalStateException("failed"));

        assertThat(counter("repoguard.review.task.created", "source", "manual_input")).isEqualTo(1.0);
        assertThat(counter(
            "repoguard.review.task.completed",
            "risk_level", "high",
            "llm_status", "fallback"
        )).isEqualTo(1.0);
        assertThat(counter(
            "repoguard.review.task.failed",
            "category", "review_execution_failed",
            "retryable", "unknown"
        )).isEqualTo(1.0);
    }

    @Test
    void recordsReviewTaskFailureWithSharedReviewFailureCategory() {
        metrics.reviewTaskFailed(new CannotAcquireLockException("deadlock"));

        assertThat(counter(
            "repoguard.review.task.failed",
            "category", "review_state_conflict",
            "retryable", "unknown"
        )).isEqualTo(1.0);
    }

    @Test
    void recordsExternalCallFailureWithClassificationTags() {
        metrics.externalCallFailed(new ExternalCallException(
            "GitHub",
            "github_rate_limited",
            true,
            429,
            "too many requests",
            new RuntimeException("429")
        ));

        assertThat(counter(
            "repoguard.external.call.failed",
            "system", "github",
            "category", "github_rate_limited",
            "retryable", "true",
            "status", "429"
        )).isEqualTo(1.0);
    }

    @Test
    void recordsGithubApiRequestsWithOperationAndResultTags() {
        metrics.githubApiRequest(Duration.ofMillis(55), "FETCH_DIFF", "SUCCESS", null, null);
        metrics.githubApiRequest(Duration.ofMillis(20), "PUBLISH_COMMENTS", "FAILED", "github_rate_limited", "429");

        assertThat(counter(
            "repoguard.github.api.request",
            "operation", "fetch_diff",
            "result", "success",
            "category", "unknown",
            "status", "unknown"
        )).isEqualTo(1.0);
        assertThat(timerCount(
            "repoguard.github.api.request.duration",
            "operation", "publish_comments",
            "result", "failed",
            "category", "github_rate_limited",
            "status", "429"
        )).isEqualTo(1);
    }

    @Test
    void recordsRabbitPublishAndCompensationCounters() {
        metrics.rabbitPublishFailed("Confirm timed out");
        metrics.rabbitPublishCompensationSucceeded();
        metrics.rabbitPublishCompensationFailed("Publisher confirm nacked");

        assertThat(counter(
            "repoguard.rabbit.publish.failed",
            "failure_phase", "publish",
            "reason", "confirm_timed_out"
        )).isEqualTo(1.0);
        metrics.rabbitPublishFailed("execute", "Recovery publish failed");
        assertThat(counter(
            "repoguard.rabbit.publish.failed",
            "failure_phase", "execute",
            "reason", "recovery_publish_failed"
        )).isEqualTo(1.0);
        assertThat(counter(
            "repoguard.rabbit.publish.compensation",
            "result", "success"
        )).isEqualTo(1.0);
        assertThat(counter(
            "repoguard.rabbit.publish.compensation",
            "result", "failed",
            "failure_phase", "publish",
            "reason", "publisher_confirm_nacked"
        )).isEqualTo(1.0);
    }

    @Test
    void recordsDurationTimersAndFallbackCounters() {
        metrics.reviewTaskDuration(Duration.ofMillis(120), "COMPLETED");
        metrics.githubDiffDuration(Duration.ofMillis(45), "SUCCESS");
        metrics.llmRequestDuration(Duration.ofMillis(80), "FAILED");
        metrics.llmFallback("category=llm_timeout retryable=true");
        metrics.githubCommentPublished("success");
        metrics.githubCommentPublishDuration(Duration.ofMillis(66), "success");
        metrics.rabbitMessageConsumed(Duration.ofMillis(35), "success");
        metrics.rabbitMessageConsumed(Duration.ofMillis(12), "rejected", "notification_timeout");
        metrics.rabbitQueueDepth("review.queue", "dlq", 7);

        assertThat(timerCount("repoguard.review.task.duration", "result", "completed")).isEqualTo(1);
        assertThat(timerTotalSeconds("repoguard.review.task.duration", "result", "completed")).isPositive();
        assertThat(timerCount("repoguard.github.diff.duration", "result", "success")).isEqualTo(1);
        assertThat(timerCount("repoguard.llm.request.duration", "result", "failed")).isEqualTo(1);
        assertThat(counter(
            "repoguard.llm.fallback",
            "reason", "category_llm_timeout_retryable_true"
        )).isEqualTo(1.0);
        assertThat(counter(
            "repoguard.github.comment.publish",
            "status", "success"
        )).isEqualTo(1.0);
        assertThat(timerCount("repoguard.github.comment.publish.duration", "result", "success")).isEqualTo(1);
        assertThat(counter(
            "repoguard.rabbit.consume",
            "result", "success",
            "failure_category", "unknown"
        )).isEqualTo(1.0);
        assertThat(timerCount(
            "repoguard.rabbit.consume.duration",
            "result", "success",
            "failure_category", "unknown"
        )).isEqualTo(1);
        assertThat(counter(
            "repoguard.rabbit.consume",
            "result", "rejected",
            "failure_category", "notification_timeout"
        )).isEqualTo(1.0);
        assertThat(meterRegistry.find("repoguard.rabbit.queue.depth")
            .tag("queue", "review.queue")
            .tag("state", "dlq")
            .gauge()
            .value()).isEqualTo(7.0);
    }

    @Test
    void recordsApiRequestDurationAndResponseBytesWithStableTags() {
        metrics.apiRequest(Duration.ofMillis(42), "get", "/api/v1/reviews/{id}", 200, "success", 128);

        assertThat(timerCount(
            "repoguard.api.request.duration",
            "method", "GET",
            "path", "/api/v1/reviews/{id}",
            "status", "200",
            "outcome", "success"
        )).isEqualTo(1);
        assertThat(summaryTotal(
            "repoguard.api.response.bytes",
            "method", "GET",
            "path", "/api/v1/reviews/{id}",
            "status", "200",
            "outcome", "success"
        )).isEqualTo(128.0);
    }

    @Test
    void recordsSqlQueryDurationAndRowsWithStableTags() {
        metrics.sqlQuery(Duration.ofMillis(18), "DashboardMapper.selectMetricStat", "SELECT", "success", 6);

        assertThat(timerCount(
            "repoguard.sql.query.duration",
            "statement", "dashboardmapper.selectmetricstat",
            "command", "select",
            "result", "success"
        )).isEqualTo(1);
        assertThat(summaryTotal(
            "repoguard.sql.query.rows",
            "statement", "dashboardmapper.selectmetricstat",
            "command", "select",
            "result", "success"
        )).isEqualTo(6.0);
        assertThat(meterRegistry.find("repoguard.sql.query.rows")
            .tag("statement", "dashboardmapper.selectmetricstat")
            .tag("command", "select")
            .tag("result", "success")
            .summary()
            .getId()
            .getBaseUnit()).isEqualTo("rows");
    }

    @Test
    void recordsDashboardCacheAccessAndOperations() {
        metrics.dashboardCacheAccess("dashboardSummary", "hit");
        metrics.dashboardCacheAccess("dashboardSummary", "miss");
        metrics.dashboardCacheOperation("dashboardSummary", "clear");

        assertThat(counter(
            "repoguard.dashboard.cache.access",
            "cache", "dashboardsummary",
            "result", "hit"
        )).isEqualTo(1.0);
        assertThat(counter(
            "repoguard.dashboard.cache.access",
            "cache", "dashboardsummary",
            "result", "miss"
        )).isEqualTo(1.0);
        assertThat(counter(
            "repoguard.dashboard.cache.operation",
            "cache", "dashboardsummary",
            "operation", "clear"
        )).isEqualTo(1.0);
    }

    @Test
    void recordsObservabilityThresholdExceededWithStableTags() {
        metrics.observabilityThresholdExceeded("API Response Bytes", "/api/v1/reviews/{id}");

        assertThat(counter(
            "repoguard.observability.threshold.exceeded",
            "signal", "api_response_bytes",
            "subject", "_api_v1_reviews_id_"
        )).isEqualTo(1.0);
    }

    private double counter(String name, String... tags) {
        return meterRegistry.find(name).tags(tags).counter().count();
    }

    private long timerCount(String name, String... tags) {
        return meterRegistry.find(name).tags(tags).timer().count();
    }

    private double timerTotalSeconds(String name, String... tags) {
        return meterRegistry.find(name).tags(tags).timer().totalTime(java.util.concurrent.TimeUnit.SECONDS);
    }

    private double summaryTotal(String name, String... tags) {
        return meterRegistry.find(name).tags(tags).summary().totalAmount();
    }
}
