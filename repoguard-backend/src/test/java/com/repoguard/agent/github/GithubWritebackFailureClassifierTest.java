package com.repoguard.agent.github;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class GithubWritebackFailureClassifierTest {

    private final GithubWritebackFailureClassifier classifier = new GithubWritebackFailureClassifier();

    @Test
    void returnsEmptySummaryWhenWritebackDidNotFail() {
        var successful = classifier.classify("completed", true, "ok");
        var skipped = classifier.classify("skipped", false, "403 forbidden");

        assertThat(successful.category()).isNull();
        assertThat(successful.reason()).isNull();
        assertThat(successful.suggestion()).isNull();
        assertThat(skipped.category()).isNull();
    }

    @ParameterizedTest
    @CsvSource({
        "category=github_token_invalid retryable=false status=401, github_token_invalid, GitHub Token 无效或已过期",
        "category=github_permission_denied retryable=false status=403, github_permission_denied, GitHub Token 权限不足",
        "category=github_target_not_found retryable=false status=404, github_target_not_found, GitHub PR 或仓库不可访问",
        "category=github_rate_limited retryable=true status=429, github_rate_limited, GitHub API 访问受限",
        "category=github_timeout retryable=true, github_writeback_timeout, GitHub 回写请求超时",
        "category=github_service_unavailable retryable=true status=503, github_service_unavailable, GitHub API 暂时不可用"
    })
    void classifiesStructuredExternalCallCategories(String message, String category, String reason) {
        var result = classifier.classify("failed", false, message);

        assertThat(result.category()).isEqualTo(category);
        assertThat(result.reason()).isEqualTo(reason);
        assertThat(result.suggestion()).isNotBlank();
    }

    @ParameterizedTest
    @CsvSource({
        "401 Bad credentials, github_token_invalid",
        "403 Resource not accessible by integration, github_permission_denied",
        "404 Not Found, github_target_not_found",
        "422 Validation Failed: line is not part of the diff, github_comment_position_invalid",
        "API rate limit exceeded, github_rate_limited",
        "Read timed out, github_writeback_timeout",
        "GitHub token is not configured, github_token_missing",
        "GitHub owner or repository is not configured, github_repository_not_configured"
    })
    void classifiesLegacyAndValidationMessages(String message, String category) {
        var result = classifier.classify("failed", false, message);

        assertThat(result.category()).isEqualTo(category);
        assertThat(result.reason()).isNotBlank();
        assertThat(result.suggestion()).isNotBlank();
    }

    @Test
    void fallsBackToGenericWritebackFailure() {
        var result = classifier.classify("failed", false, "unexpected response");

        assertThat(result.category()).isEqualTo("github_writeback_failed");
        assertThat(result.reason()).isEqualTo("GitHub 评论回写失败");
        assertThat(result.suggestion()).contains("原始错误信息");
    }
}
