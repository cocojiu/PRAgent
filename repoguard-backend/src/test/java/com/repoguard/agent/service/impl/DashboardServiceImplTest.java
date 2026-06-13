package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.repoguard.agent.dto.SystemHealthItemDto;
import com.repoguard.agent.entity.IntegrationConfig;
import com.repoguard.agent.entity.ReviewFinding;
import com.repoguard.agent.entity.ReviewPolicyConfig;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.IntegrationConfigMapper;
import com.repoguard.agent.mapper.ReviewFindingMapper;
import com.repoguard.agent.mapper.ReviewPolicyConfigMapper;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.security.SecretCryptoService;
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
    private final IntegrationConfigMapper integrationConfigMapper = org.mockito.Mockito.mock(IntegrationConfigMapper.class);
    private final ReviewPolicyConfigMapper reviewPolicyConfigMapper = org.mockito.Mockito.mock(ReviewPolicyConfigMapper.class);
    private final RabbitTemplate rabbitTemplate = org.mockito.Mockito.mock(RabbitTemplate.class);
    private final SecretCryptoService secretCryptoService = new SecretCryptoService("test-encryption-key");
    private final DashboardServiceImpl service = new DashboardServiceImpl(
        reviewTaskMapper,
        reviewFindingMapper,
        integrationConfigMapper,
        reviewPolicyConfigMapper,
        rabbitTemplate,
        secretCryptoService
    );

    @Test
    void overviewReportsConfiguredDependenciesAsHealthy() {
        when(reviewTaskMapper.selectList(any())).thenReturn(List.of());
        when(reviewFindingMapper.selectList(any())).thenReturn(List.of());
        when(rabbitTemplate.execute(org.mockito.ArgumentMatchers.<ChannelCallback<Boolean>>any())).thenReturn(true);
        when(integrationConfigMapper.selectOne(any())).thenReturn(githubConfig("CONFIGURED", "ghp_test"));
        when(reviewPolicyConfigMapper.selectById(1L)).thenReturn(reviewPolicyConfig("sk-test"));

        Map<String, String> health = healthByName(service.getOverview().systemHealth());

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
        when(integrationConfigMapper.selectOne(any())).thenReturn(githubConfig("FAILED", "ghp_test"));
        when(reviewPolicyConfigMapper.selectById(1L)).thenReturn(null);

        Map<String, String> health = healthByName(service.getOverview().systemHealth());

        assertThat(health).containsEntry("RabbitMQ", "异常");
        assertThat(health).containsEntry("GitHub", "异常");
        assertThat(health).containsEntry("Spring AI", "未接入");
    }

    @Test
    void overviewReportsLlmQualityByModelAndRepository() {
        when(reviewTaskMapper.selectList(any())).thenReturn(List.of(
            task(1L, "octocat", "api", "dashscope", "qwen-plus", "COMPLETED", "PARSED", 1200),
            task(2L, "octocat", "api", "dashscope", "qwen-plus", "FALLBACK", "FALLBACK", 2200),
            task(3L, "octocat", "web", "openai", "gpt-test", "COMPLETED", "PARSED", 800)
        ));
        when(reviewFindingMapper.selectList(any())).thenReturn(List.of(
            finding(1L, "VALID"),
            finding(1L, "FALSE_POSITIVE"),
            finding(2L, "VALID"),
            finding(3L, "UNREVIEWED")
        ));
        when(rabbitTemplate.execute(org.mockito.ArgumentMatchers.<ChannelCallback<Boolean>>any())).thenReturn(true);
        when(integrationConfigMapper.selectOne(any())).thenReturn(githubConfig("CONFIGURED", "ghp_test"));
        when(reviewPolicyConfigMapper.selectById(1L)).thenReturn(reviewPolicyConfig("sk-test"));

        var overview = service.getOverview();

        assertThat(overview.llmQualityByModel()).hasSize(2);
        var qwen = overview.llmQualityByModel().stream()
            .filter(item -> "dashscope / qwen-plus".equals(item.model()))
            .findFirst()
            .orElseThrow();
        assertThat(qwen.taskCount()).isEqualTo(2);
        assertThat(qwen.parseSuccessRate()).isEqualTo("50.0%");
        assertThat(qwen.fallbackRate()).isEqualTo("50.0%");
        assertThat(qwen.validRate()).isEqualTo("66.7%");
        assertThat(qwen.falsePositiveRate()).isEqualTo("33.3%");

        var api = overview.llmQualityByRepository().stream()
            .filter(item -> "octocat/api".equals(item.repository()))
            .findFirst()
            .orElseThrow();
        assertThat(api.taskCount()).isEqualTo(2);
        assertThat(api.fallbackRate()).isEqualTo("50.0%");
        assertThat(api.validRate()).isEqualTo("66.7%");
        assertThat(overview.llmQualityTrend()).hasSize(7);
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

    private IntegrationConfig githubConfig(String status, String token) {
        IntegrationConfig config = new IntegrationConfig();
        config.setProvider("GITHUB");
        config.setStatus(status);
        config.setBaseUrl("https://api.github.com");
        config.setTokenValue(token);
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
