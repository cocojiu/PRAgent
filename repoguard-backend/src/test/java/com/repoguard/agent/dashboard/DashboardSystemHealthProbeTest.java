package com.repoguard.agent.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.repoguard.agent.config.GithubIntegrationProvider;
import com.repoguard.agent.config.GithubIntegrationSettings;
import com.repoguard.agent.config.ReviewPolicyProvider;
import com.repoguard.agent.config.ReviewPolicySettings;
import com.repoguard.agent.dto.SystemHealthItemDto;
import com.repoguard.agent.service.impl.RabbitRuntimeHealthProbe;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class DashboardSystemHealthProbeTest {

    private final GithubIntegrationProvider githubIntegrationProvider =
        org.mockito.Mockito.mock(GithubIntegrationProvider.class);
    private final ReviewPolicyProvider reviewPolicyProvider =
        org.mockito.Mockito.mock(ReviewPolicyProvider.class);
    private final RabbitRuntimeHealthProbe rabbitRuntimeHealthProbe =
        org.mockito.Mockito.mock(RabbitRuntimeHealthProbe.class);
    private final DashboardSystemHealthProbe probe = new DashboardSystemHealthProbe(
        githubIntegrationProvider,
        reviewPolicyProvider,
        rabbitRuntimeHealthProbe,
        new DashboardStatusMapper()
    );

    @Test
    void reportsConfiguredDependenciesAsHealthy() {
        when(rabbitRuntimeHealthProbe.connectionStatus()).thenReturn("CONNECTED");
        when(githubIntegrationProvider.getSettings()).thenReturn(githubSettings("CONFIGURED", "ghp_test"));
        when(reviewPolicyProvider.getSettings()).thenReturn(reviewPolicySettings("sk-test"));

        Map<String, String> health = healthByName(probe.probe());

        assertThat(health).containsEntry("MySQL", "正常");
        assertThat(health).containsEntry("RabbitMQ", "正常");
        assertThat(health).containsEntry("GitHub", "正常");
        assertThat(health).containsEntry("Spring AI", "正常");
    }

    @Test
    void keepsRenderingWhenDependencyHealthChecksFail() {
        when(rabbitRuntimeHealthProbe.connectionStatus()).thenReturn("DISCONNECTED");
        when(githubIntegrationProvider.getSettings()).thenReturn(githubSettings("FAILED", "ghp_test"));
        when(reviewPolicyProvider.getSettings()).thenReturn(ReviewPolicySettings.empty());

        Map<String, String> health = healthByName(probe.probe());

        assertThat(health).containsEntry("MySQL", "正常");
        assertThat(health).containsEntry("RabbitMQ", "异常");
        assertThat(health).containsEntry("GitHub", "异常");
        assertThat(health).containsEntry("Spring AI", "未接入");
    }

    private Map<String, String> healthByName(List<SystemHealthItemDto> healthItems) {
        return healthItems.stream().collect(Collectors.toMap(SystemHealthItemDto::name, SystemHealthItemDto::status));
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
