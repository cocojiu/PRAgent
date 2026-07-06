package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.entity.ReviewTask;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReviewFailureSummaryResolverTest {

    private final ReviewFailureSummaryResolver resolver = new ReviewFailureSummaryResolver();

    @Test
    void returnsEmptySummaryForNonFailedTask() {
        ReviewTask task = task("COMPLETED");

        var result = resolver.resolve(task, List.of("Review failed: category=github_token_invalid"));

        assertThat(result.category()).isNull();
        assertThat(result.reason()).isNull();
        assertThat(result.suggestion()).isNull();
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
