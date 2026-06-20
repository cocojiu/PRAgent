package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.config.GithubIntegrationSettings;
import com.repoguard.agent.entity.ReviewTask;
import org.junit.jupiter.api.Test;

class GithubCommentWritebackCheckBuilderTest {

    private final GithubCommentWritebackCheckBuilder builder = new GithubCommentWritebackCheckBuilder();

    @Test
    void buildReturnsReadyWhenTokenAndRepositoryMatch() {
        var check = builder.build(task("octocat", "Hello-World"), settings("octocat", "Hello-World", "CONFIGURED", "ghp_test", null));

        assertThat(check.status()).isEqualTo("ready");
        assertThat(check.level()).isEqualTo("success");
        assertThat(check.repositoryMatched()).isTrue();
        assertThat(check.tokenConfigured()).isTrue();
        assertThat(check.connectionHealthy()).isTrue();
    }

    @Test
    void buildReturnsRepositoryMismatchWhenDefaultRepositoryDiffers() {
        var check = builder.build(task("octocat", "Hello-World"), settings("another", "repo", "CONFIGURED", "ghp_test", null));

        assertThat(check.status()).isEqualTo("repository_mismatch");
        assertThat(check.level()).isEqualTo("warning");
        assertThat(check.repositoryMatched()).isFalse();
        assertThat(check.messages()).anyMatch(message -> message.contains("不一致"));
    }

    @Test
    void buildReturnsTokenMissingBeforeRepositoryReadiness() {
        var check = builder.build(task("octocat", "Hello-World"), settings("octocat", "Hello-World", "CONFIGURED", "", null));

        assertThat(check.status()).isEqualTo("token_missing");
        assertThat(check.level()).isEqualTo("danger");
        assertThat(check.tokenConfigured()).isFalse();
        assertThat(check.connectionHealthy()).isFalse();
    }

    private ReviewTask task(String owner, String repository) {
        ReviewTask task = new ReviewTask();
        task.setOrganization(owner);
        task.setRepository(repository);
        return task;
    }

    private GithubIntegrationSettings settings(
        String owner,
        String repository,
        String status,
        String token,
        String lastError
    ) {
        return new GithubIntegrationSettings(
            "GITHUB",
            status,
            "https://api.github.com",
            token,
            lastError,
            owner,
            repository,
            1L
        );
    }
}
