package com.repoguard.agent.review.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.review.task.ReviewFailureSummaryResolver.ReviewFailureDetails;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReviewFailureSummaryResolverTest {

    private final ReviewFailureSummaryResolver resolver = new ReviewFailureSummaryResolver(new com.repoguard.agent.config.JacksonConfig().objectMapper());

    @Test
    void structuredSnapshotIgnoresPresentationTextAndKeepsRetryHint() {
        ReviewTask task = task("FAILED");
        task.setCurrentAttemptId(42L);
        task.setFailureDetailsJson(new ReviewFailureDetails("llm_rate_limited", true, "LLM", 42L, "60").toJson(new com.repoguard.agent.config.JacksonConfig().objectMapper()));
        var result = resolver.resolve(task, List.of("Renamed failure event", "Review failed: category=github_token_invalid"));
        assertThat(result.category()).isEqualTo("llm_rate_limited");
        assertThat(result.suggestion()).contains("60");
    }

    @Test
    void unknownMalformedAndStaleSnapshotsDoNotReuseLegacyClassification() {
        ReviewTask task = task("FAILED");
        task.setCurrentAttemptId(42L);
        for (String snapshot : List.of("not-json",
            new ReviewFailureDetails("future_category", false, "REVIEW_EXECUTION", 42L, "").toJson(new com.repoguard.agent.config.JacksonConfig().objectMapper()),
            new ReviewFailureDetails("llm_timeout", true, "LLM", 41L, "").toJson(new com.repoguard.agent.config.JacksonConfig().objectMapper()))) {
            task.setFailureDetailsJson(snapshot);
            assertThat(resolver.resolve(task, List.of("Review failed: category=github_token_invalid")).category())
                .isEqualTo("review_execution_failed");
        }
    }

    @Test
    void returnsEmptySummaryForNonFailedTask() {
        ReviewTask task = task("COMPLETED");

        var result = resolver.resolve(task, List.of("Review failed: category=github_token_invalid"));

        assertThat(result.category()).isNull();
        assertThat(result.reason()).isNull();
        assertThat(result.suggestion()).isNull();
    }

    @Test
    void resolvesSupersededTaskAsLatestHeadRetryGuidance() {
        ReviewTask task = task("SUPERSEDED");

        var result = resolver.resolve(task, List.of("Review superseded: expected aaa, current bbb"));

        assertThat(result.category()).isEqualTo("review_superseded");
        assertThat(result.reason()).contains("PR Head 已变化");
        assertThat(result.suggestion()).contains("最新提交");
    }

    @Test
    void resolvesLatestStructuredFailureCategory() {
        ReviewTask task = task("FAILED");

        var result = resolver.resolve(task, List.of(
            "Review failed: category=github_token_invalid",
            "Review failed: category=llm_timeout"
        ));

        assertThat(result.category()).isEqualTo("llm_timeout");
        assertThat(result.reason()).isEqualTo("LLM 响应超时");
        assertThat(result.suggestion()).contains("超时配置");
    }

    @Test
    void includesRetryAfterHintForStructuredRateLimitFailure() {
        ReviewTask task = task("FAILED");

        var result = resolver.resolve(task, List.of(
            "Review failed: GitHub external call failed: category=github_rate_limited retryable=true status=429 detail=API rate limit exceeded retryAfter=60 responseBody={}"
        ));

        assertThat(result.category()).isEqualTo("github_rate_limited");
        assertThat(result.reason()).isEqualTo("GitHub API 访问受限");
        assertThat(result.suggestion()).contains("建议等待 60 后再重试", "更换剩余额度充足的 GitHub Token");
    }

    @Test
    void includesRetryAfterHintForLlmRateLimitFailure() {
        ReviewTask task = task("FAILED");

        var result = resolver.resolve(task, List.of(
            "Review failed: LLM external call failed: category=llm_rate_limited retryable=true status=429 detail=Too Many Requests retryAfter=120 responseBody={}"
        ));

        assertThat(result.category()).isEqualTo("llm_rate_limited");
        assertThat(result.reason()).isEqualTo("LLM 调用受限");
        assertThat(result.suggestion()).contains("建议等待 120 后再重试", "供应商额度");
    }

    @Test
    void resolvesLegacyGithubPermissionFailureText() {
        ReviewTask task = task("FAILED");

        var result = resolver.resolve(task, List.of(
            "Review failed: GitHub returned 403 Resource not accessible by integration"
        ));

        assertThat(result.category()).isEqualTo("github_permission_denied");
        assertThat(result.reason()).isEqualTo("GitHub Token 权限不足");
        assertThat(result.suggestion()).contains("repo 权限");
    }

    @Test
    void fallsBackToGenericReviewExecutionFailure() {
        ReviewTask task = task("FAILED");

        var result = resolver.resolve(task, List.of("Review failed: unexpected worker exception"));

        assertThat(result.category()).isEqualTo("review_execution_failed");
        assertThat(result.reason()).isEqualTo("审查执行失败");
    }

    private ReviewTask task(String status) {
        ReviewTask task = new ReviewTask();
        task.setStatus(status);
        return task;
    }
}
