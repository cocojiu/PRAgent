package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.repoguard.agent.config.GithubIntegrationProvider;
import com.repoguard.agent.config.GithubIntegrationSettings;
import com.repoguard.agent.config.ReviewPolicyProvider;
import com.repoguard.agent.config.ReviewPolicySettings;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.messaging.RabbitRuntimeHealthProbe;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
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

    @Test
    void getNotificationsBuildsItemsFromTasksAndIntegrationStatus() {
        when(reviewTaskMapper.selectList(any())).thenReturn(List.of(
            task(1L, "FAILED", "HIGH", "FAILED"),
            task(2L, "COMPLETED", "MEDIUM", "FALLBACK")
        ));
        when(githubIntegrationProvider.getSettings()).thenReturn(githubSettings("FAILED", "ghp_test", "bad token"));
        when(rabbitRuntimeHealthProbe.connectionStatus()).thenReturn("DISCONNECTED");
        when(reviewPolicyProvider.getSettings()).thenReturn(reviewPolicySettings(""));

        var result = service.getNotifications();

        assertThat(result.total()).isGreaterThanOrEqualTo(5);
        assertThat(result.items()).extracting("id").contains(
            "review-failed-1",
            "review-high-risk-1",
            "review-llm-fallback-2",
            "integration-github-failed",
            "integration-rabbitmq-failed",
            "llm-missing-secret"
        );
        assertThat(result.items().getFirst().targetPath()).startsWith("/repoguard/");
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
        task.setRiskLevel(riskLevel);
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
