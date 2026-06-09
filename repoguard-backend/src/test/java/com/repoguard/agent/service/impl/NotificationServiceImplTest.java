package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.repoguard.agent.entity.IntegrationConfig;
import com.repoguard.agent.entity.ReviewPolicyConfig;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.IntegrationConfigMapper;
import com.repoguard.agent.mapper.ReviewPolicyConfigMapper;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.security.SecretCryptoService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.ChannelCallback;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class NotificationServiceImplTest {

    private final ReviewTaskMapper reviewTaskMapper = org.mockito.Mockito.mock(ReviewTaskMapper.class);
    private final IntegrationConfigMapper integrationConfigMapper = org.mockito.Mockito.mock(IntegrationConfigMapper.class);
    private final ReviewPolicyConfigMapper reviewPolicyConfigMapper = org.mockito.Mockito.mock(ReviewPolicyConfigMapper.class);
    private final RabbitTemplate rabbitTemplate = org.mockito.Mockito.mock(RabbitTemplate.class);
    private final SecretCryptoService secretCryptoService = new SecretCryptoService("test-encryption-key");
    private final NotificationServiceImpl service = new NotificationServiceImpl(
        reviewTaskMapper,
        integrationConfigMapper,
        reviewPolicyConfigMapper,
        rabbitTemplate,
        secretCryptoService
    );

    @Test
    void getNotificationsBuildsItemsFromTasksAndIntegrationStatus() {
        when(reviewTaskMapper.selectList(any())).thenReturn(List.of(
            task(1L, "FAILED", "HIGH", "FAILED"),
            task(2L, "COMPLETED", "MEDIUM", "FALLBACK")
        ));
        when(integrationConfigMapper.selectOne(any())).thenReturn(githubConfig("FAILED", "ghp_test", "bad token"));
        when(rabbitTemplate.execute(org.mockito.ArgumentMatchers.<ChannelCallback<Boolean>>any())).thenReturn(false);
        when(reviewPolicyConfigMapper.selectById(1L)).thenReturn(reviewPolicyConfig(""));

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

    private IntegrationConfig githubConfig(String status, String token, String lastError) {
        IntegrationConfig config = new IntegrationConfig();
        config.setProvider("GITHUB");
        config.setStatus(status);
        config.setBaseUrl("https://api.github.com");
        config.setTokenValue(token);
        config.setLastError(lastError);
        return config;
    }

    private ReviewPolicyConfig reviewPolicyConfig(String apiKey) {
        ReviewPolicyConfig config = new ReviewPolicyConfig();
        config.setId(1L);
        config.setLlmEnabled(true);
        config.setLlmProvider("dashscope");
        config.setModelName("qwen-plus");
        config.setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        config.setApiKeyValue(apiKey);
        config.setTimeoutSeconds(60);
        config.setTemperature(BigDecimal.valueOf(0.20));
        config.setMaxTokens(4096);
        config.setFallbackToRules(true);
        config.setWorkerConcurrency(1);
        return config;
    }
}
