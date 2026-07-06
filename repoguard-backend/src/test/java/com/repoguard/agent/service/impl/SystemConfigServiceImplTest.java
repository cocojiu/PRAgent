package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.dto.BaseSettingsRequest;
import com.repoguard.agent.dto.GithubIntegrationConfigRequest;
import com.repoguard.agent.dto.NotificationSettingsRequest;
import com.repoguard.agent.dto.ReviewPolicyConfigRequest;
import com.repoguard.agent.dto.ReviewPolicySettingsRequest;
import com.repoguard.agent.dto.ReviewRuleFeedbackStat;
import com.repoguard.agent.dto.ReviewRuleHitCount;
import com.repoguard.agent.dto.SecuritySettingsRequest;
import com.repoguard.agent.dto.ServiceIntegrationConfigRequest;
import com.repoguard.agent.dto.SystemSettingsRequest;
import com.repoguard.agent.entity.IntegrationConfig;
import com.repoguard.agent.entity.ReviewPolicyConfig;
import com.repoguard.agent.entity.ReviewRuleConfig;
import com.repoguard.agent.entity.SystemSettingLog;
import com.repoguard.agent.entity.SystemSettingsConfig;
import com.repoguard.agent.mapper.IntegrationConfigMapper;
import com.repoguard.agent.mapper.ReviewFindingMapper;
import com.repoguard.agent.mapper.ReviewPolicyConfigMapper;
import com.repoguard.agent.mapper.ReviewRuleConfigMapper;
import com.repoguard.agent.mapper.SystemSettingLogMapper;
import com.repoguard.agent.mapper.SystemSettingsConfigMapper;
import com.repoguard.agent.review.LlmConnectionProbeResponseParser;
import com.repoguard.agent.review.LlmReviewFindingMapper;
import com.repoguard.agent.review.LlmReviewJsonExtractor;
import com.repoguard.agent.review.LlmReviewParseFailureSummarizer;
import com.repoguard.agent.review.LlmReviewResultParser;
import com.repoguard.agent.review.LlmReviewSchemaRepairer;
import com.repoguard.agent.security.SecretCryptoService;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.RestClient;

class SystemConfigServiceImplTest {

    private final IntegrationConfigMapper integrationConfigMapper = org.mockito.Mockito.mock(IntegrationConfigMapper.class);
    private final ReviewPolicyConfigMapper reviewPolicyConfigMapper = org.mockito.Mockito.mock(ReviewPolicyConfigMapper.class);
    private final ReviewRuleConfigMapper reviewRuleConfigMapper = org.mockito.Mockito.mock(ReviewRuleConfigMapper.class);
    private final ReviewFindingMapper reviewFindingMapper = org.mockito.Mockito.mock(ReviewFindingMapper.class);
    private final SystemSettingsConfigMapper systemSettingsConfigMapper = org.mockito.Mockito.mock(SystemSettingsConfigMapper.class);
    private final SystemSettingLogMapper systemSettingLogMapper = org.mockito.Mockito.mock(SystemSettingLogMapper.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SecretCryptoService secretCryptoService = new SecretCryptoService("test-encryption-key");
    private final ConnectionTestServiceImpl connectionTestService = new ConnectionTestServiceImpl(
        integrationConfigMapper,
        reviewPolicyConfigMapper,
        RestClient.builder(),
        objectMapper,
        null,
        null,
        secretCryptoService,
        responseParser()
    );
    private final SystemIntegrationConfigServiceImpl systemIntegrationConfigService =
        new SystemIntegrationConfigServiceImpl(
            integrationConfigMapper,
            secretCryptoService,
            null,
            null
        );
    private final ReviewPolicyConfigServiceImpl reviewPolicyConfigService = new ReviewPolicyConfigServiceImpl(
        reviewPolicyConfigMapper,
        secretCryptoService
    );
    private final ReviewRuleConfigServiceImpl reviewRuleConfigService = new ReviewRuleConfigServiceImpl(
        reviewRuleConfigMapper,
        reviewFindingMapper,
        null,
        new ReviewRuleConfigPolicy(),
        new ReviewRuleMetricAssembler()
    );
    private final SystemSettingsApplicationServiceImpl systemSettingsApplicationService =
        new SystemSettingsApplicationServiceImpl(
            systemSettingsConfigMapper,
            systemSettingLogMapper,
            reviewPolicyConfigMapper
        );
    private final SystemConfigServiceImpl service = new SystemConfigServiceImpl(
        connectionTestService,
        systemIntegrationConfigService,
        reviewPolicyConfigService,
        reviewRuleConfigService,
        systemSettingsApplicationService
    );

    @Test
    void constructorRejectsMissingReviewRuleConfigService() {
        assertThatThrownBy(() -> new SystemConfigServiceImpl(
            connectionTestService,
            systemIntegrationConfigService,
            reviewPolicyConfigService,
            null,
            systemSettingsApplicationService
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("reviewRuleConfigService");
    }

    @Test
    void updateGithubIntegrationMasksTokenAndStoresNewSecret() {
        IntegrationConfig config = githubConfig("old-token");
        when(integrationConfigMapper.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(config);

        var result = service.updateGithubIntegration(new GithubIntegrationConfigRequest(
            "https://api.github.com",
            "ghp_new_secret_1234",
            "repo-guard-demo",
            "spring-boot-demo"
        ));

        assertThat(config.getTokenValue()).startsWith("enc:v2:local:");
        assertThat(secretCryptoService.decrypt(config.getTokenValue())).isEqualTo("ghp_new_secret_1234");
        assertThat(config.getStatus()).isEqualTo("CONFIGURED");
        assertThat(result.token()).isEqualTo("****1234");
        verify(integrationConfigMapper).updateById(config);
    }

    @Test
    void updateReviewPolicyKeepsExistingApiKeyWhenMaskedValueIsSubmitted() {
        ReviewPolicyConfig config = reviewPolicyConfig("sk-existing-5678");
        when(reviewPolicyConfigMapper.selectById(1L)).thenReturn(config);

        var result = service.updateReviewPolicy(new ReviewPolicyConfigRequest(
            true,
            "dashscope",
            "qwen-plus",
            "https://dashscope.aliyuncs.com/compatible-mode/v1",
            "****5678",
            90,
            BigDecimal.valueOf(0.30),
            8192,
            true,
            2,
            6,
            700,
            4,
            450,
            BigDecimal.valueOf(0.50),
            BigDecimal.valueOf(1.50)
        ));

        assertThat(config.getApiKeyValue()).startsWith("enc:v2:local:");
        assertThat(secretCryptoService.decrypt(config.getApiKeyValue())).isEqualTo("sk-existing-5678");
        assertThat(config.getTimeoutSeconds()).isEqualTo(90);
        assertThat(config.getWorkerConcurrency()).isEqualTo(2);
        assertThat(config.getChunkFileThreshold()).isEqualTo(6);
        assertThat(config.getInputTokenPricePerMillion()).isEqualByComparingTo("0.50");
        assertThat(result.apiKey()).isEqualTo("****5678");
        assertThat(result.chunkLineThreshold()).isEqualTo(700);
        assertThat(result.outputTokenPricePerMillion()).isEqualByComparingTo("1.50");
        verify(reviewPolicyConfigMapper).updateById(config);
    }

    @Test
    void updateGithubIntegrationClearsTokenWhenBlankValueIsSubmitted() {
        IntegrationConfig config = githubConfig("old-token");
        when(integrationConfigMapper.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(config);

        var result = service.updateGithubIntegration(new GithubIntegrationConfigRequest(
            "https://api.github.com",
            "",
            "repo-guard-demo",
            "spring-boot-demo"
        ));

        assertThat(config.getTokenValue()).isNull();
        assertThat(config.getStatus()).isEqualTo("NOT_CONFIGURED");
        assertThat(result.token()).isNull();
        verify(integrationConfigMapper).updateById(config);
        verify(integrationConfigMapper, org.mockito.Mockito.times(2)).update(any(UpdateWrapper.class));
    }

    @Test
    void updateReviewPolicyClearsApiKeyWhenBlankValueIsSubmitted() {
        ReviewPolicyConfig config = reviewPolicyConfig("sk-existing-5678");
        when(reviewPolicyConfigMapper.selectById(1L)).thenReturn(config);

        var result = service.updateReviewPolicy(new ReviewPolicyConfigRequest(
            true,
            "dashscope",
            "qwen-plus",
            "https://dashscope.aliyuncs.com/compatible-mode/v1",
            "",
            90,
            BigDecimal.valueOf(0.30),
            8192,
            true,
            2,
            6,
            700,
            4,
            450,
            BigDecimal.valueOf(0.50),
            BigDecimal.valueOf(1.50)
        ));

        assertThat(config.getApiKeyValue()).isNull();
        assertThat(result.apiKey()).isNull();
        verify(reviewPolicyConfigMapper).updateById(config);
        verify(reviewPolicyConfigMapper).update(any(UpdateWrapper.class));
    }

    @Test
    void updateMysqlIntegrationKeepsExistingSecretWhenMaskedValueIsSubmitted() {
        IntegrationConfig config = serviceConfig("MYSQL", "mysql-existing-1234");
        when(integrationConfigMapper.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(config);

        var result = service.updateMysqlIntegration(new ServiceIntegrationConfigRequest(
            "jdbc:mysql://localhost:3306/repoguard",
            "root",
            "****1234",
            "repoguard"
        ));

        assertThat(config.getTokenValue()).startsWith("enc:v2:local:");
        assertThat(secretCryptoService.decrypt(config.getTokenValue())).isEqualTo("mysql-existing-1234");
        assertThat(config.getStatus()).isEqualTo("CONFIGURED");
        assertThat(result.secret()).isEqualTo("****1234");
        verify(integrationConfigMapper).updateById(config);
    }

    @Test
    void getSystemSettingsCreatesDefaultsWhenMissing() {
        var result = service.getSystemSettings();

        assertThat(result.base().systemName()).isEqualTo("RepoGuard Agent");
        assertThat(result.base().language()).isEqualTo("中文");
        assertThat(result.policy().maxDiffLines()).isEqualTo(800);
        assertThat(result.policy().llmTimeoutSeconds()).isEqualTo(60);
        assertThat(result.notification().email()).isEqualTo("ops@repoguard.dev");
        assertThat(result.security().webhookSignature()).isTrue();
        verify(systemSettingsConfigMapper).insert(any(SystemSettingsConfig.class));
        verify(reviewPolicyConfigMapper).insert(any(ReviewPolicyConfig.class));
    }

    @Test
    void updateSystemSettingsPersistsConfigAndRecordsLog() {
        SystemSettingsConfig settingsConfig = systemSettingsConfig();
        ReviewPolicyConfig reviewPolicyConfig = reviewPolicyConfig("sk-existing-5678");
        when(systemSettingsConfigMapper.selectById(1L)).thenReturn(settingsConfig);
        when(reviewPolicyConfigMapper.selectById(1L)).thenReturn(reviewPolicyConfig);
        when(systemSettingLogMapper.selectList(any())).thenReturn(List.of(settingLog()));

        var result = service.updateSystemSettings(new SystemSettingsRequest(
            new BaseSettingsRequest("RepoGuard Agent Pro", "中文", "Asia/Shanghai", 120),
            new ReviewPolicySettingsRequest(1200, 90, 3, true, false),
            new NotificationSettingsRequest(true, false, true, "devops@repoguard.dev"),
            new SecuritySettingsRequest(true, true, true, 45)
        ));

        assertThat(settingsConfig.getSystemName()).isEqualTo("RepoGuard Agent Pro");
        assertThat(settingsConfig.getRetentionDays()).isEqualTo(120);
        assertThat(settingsConfig.getMaxDiffLines()).isEqualTo(1200);
        assertThat(settingsConfig.getAutoRetry()).isFalse();
        assertThat(settingsConfig.getHighRiskPr()).isFalse();
        assertThat(settingsConfig.getNotificationEmail()).isEqualTo("devops@repoguard.dev");
        assertThat(settingsConfig.getPublicRepoAllowed()).isTrue();
        assertThat(settingsConfig.getTokenTtlDays()).isEqualTo(45);
        assertThat(reviewPolicyConfig.getTimeoutSeconds()).isEqualTo(90);
        assertThat(reviewPolicyConfig.getWorkerConcurrency()).isEqualTo(3);
        assertThat(secretCryptoService.decrypt(reviewPolicyConfig.getApiKeyValue())).isEqualTo("sk-existing-5678");
        assertThat(result.policy().workerConcurrency()).isEqualTo(3);
        assertThat(result.logs()).hasSize(1);
        verify(systemSettingsConfigMapper).updateById(settingsConfig);
        verify(reviewPolicyConfigMapper).updateById(reviewPolicyConfig);
        ArgumentCaptor<SystemSettingLog> logCaptor = ArgumentCaptor.forClass(SystemSettingLog.class);
        verify(systemSettingLogMapper).insert(logCaptor.capture());
        assertThat(logCaptor.getValue().getOperator()).isEqualTo("admin");
        assertThat(logCaptor.getValue().getAction()).isEqualTo("更新系统设置");
        assertThat(logCaptor.getValue().getStatus()).isEqualTo("成功");
    }

    @Test
    void testGithubIntegrationClearsStaleErrorOnSuccess() throws Exception {
        try (ProbeServer server = startProbeServer("/rate_limit", 200, "{}")) {
            IntegrationConfig config = githubConfig("ghp_test_1234");
            config.setBaseUrl(server.baseUrl());
            config.setStatus("FAILED");
            config.setLastError("stale GitHub error");
            when(integrationConfigMapper.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(config);

            var result = service.testGithubIntegration(null);

            assertThat(result.success()).isTrue();
            assertThat(config.getStatus()).isEqualTo("CONFIGURED");
            assertThat(config.getLastError()).isNull();
            assertThat(config.getLastCheckedAt()).isNotNull();
            assertThat(server.authorization()).isEqualTo("Bearer ghp_test_1234");
            verify(integrationConfigMapper).updateById(config);
            verify(integrationConfigMapper).update(any(UpdateWrapper.class));
        }
    }

    @Test
    void testGithubIntegrationUsesCurrentFormConfigWithoutPersistingStatus() throws Exception {
        try (ProbeServer server = startProbeServer("/rate_limit", 200, "{}")) {
            IntegrationConfig savedConfig = githubConfig("ghp_saved_1234");
            savedConfig.setBaseUrl("https://api.github.com");
            when(integrationConfigMapper.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(savedConfig);

            var result = service.testGithubIntegration(new GithubIntegrationConfigRequest(
                server.baseUrl(),
                "****1234",
                null,
                null
            ));

            assertThat(result.success()).isTrue();
            assertThat(server.authorization()).isEqualTo("Bearer ghp_saved_1234");
            assertThat(savedConfig.getBaseUrl()).isEqualTo("https://api.github.com");
            verify(integrationConfigMapper, org.mockito.Mockito.never()).updateById(org.mockito.ArgumentMatchers.any(IntegrationConfig.class));
            verify(integrationConfigMapper, org.mockito.Mockito.never()).update(any(UpdateWrapper.class));
        }
    }

    @Test
    void testGithubIntegrationRecordsLatestErrorOnFailure() throws Exception {
        try (ProbeServer server = startProbeServer("/rate_limit", 500, "{\"message\":\"boom\"}")) {
            IntegrationConfig config = githubConfig("ghp_test_1234");
            config.setBaseUrl(server.baseUrl());
            when(integrationConfigMapper.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(config);

            var result = service.testGithubIntegration(null);

            assertThat(result.success()).isFalse();
            assertThat(config.getStatus()).isEqualTo("FAILED");
            assertThat(config.getLastError()).contains("500");
            assertThat(config.getLastCheckedAt()).isNotNull();
            verify(integrationConfigMapper).updateById(config);
        }
    }

    @Test
    void testReviewPolicyParsesChatCompletionSmokeResponse() throws Exception {
        String llmResponse = """
            {"choices":[{"message":{"content":"{\\"riskLevel\\":\\"INFO\\",\\"findings\\":[]}"}}]}
            """;
        try (ProbeServer server = startLlmProbeServer(llmResponse)) {
            ReviewPolicyConfig config = reviewPolicyConfig("sk-test-1234");
            config.setBaseUrl(server.baseUrl());
            config.setMaxTokens(64);
            when(reviewPolicyConfigMapper.selectById(1L)).thenReturn(config);

            var result = service.testReviewPolicy(null);

            assertThat(result.success()).isTrue();
            assertThat(result.status()).isEqualTo("connected");
            assertThat(server.authorization()).isEqualTo("Bearer sk-test-1234");
            JsonNode request = new ObjectMapper().readTree(server.requestBody());
            assertThat(request.path("model").asText()).isEqualTo("qwen-plus");
            assertThat(request.path("max_tokens").asInt()).isGreaterThanOrEqualTo(512);
            assertThat(request.at("/messages/1/content").asText()).contains("riskLevel");
        }
    }

    @Test
    void testReviewPolicyAcceptsContentPartArrayResponse() throws Exception {
        String llmResponse = """
            {"choices":[{"message":{"content":[{"type":"text","text":"```json\\n{\\"riskLevel\\":\\"INFO\\",\\"findings\\":[]}\\n```"}]}}]}
            """;
        try (ProbeServer server = startLlmProbeServer(llmResponse)) {
            ReviewPolicyConfig config = reviewPolicyConfig("sk-test-1234");
            config.setBaseUrl(server.baseUrl());
            when(reviewPolicyConfigMapper.selectById(1L)).thenReturn(config);

            var result = service.testReviewPolicy(null);

            assertThat(result.success()).isTrue();
            assertThat(result.status()).isEqualTo("connected");
        }
    }

    @Test
    void testReviewPolicyReportsMalformedReviewJson() throws Exception {
        String llmResponse = """
            {"choices":[{"message":{"content":"OK"}}]}
            """;
        try (ProbeServer server = startLlmProbeServer(llmResponse)) {
            ReviewPolicyConfig config = reviewPolicyConfig("sk-test-1234");
            config.setBaseUrl(server.baseUrl());
            when(reviewPolicyConfigMapper.selectById(1L)).thenReturn(config);

            var result = service.testReviewPolicy(null);

            assertThat(result.success()).isFalse();
            assertThat(result.status()).isEqualTo("failed");
            assertThat(result.message()).contains("could not be parsed as review JSON");
        }
    }

    @Test
    void testMysqlConnectionReportsSubmittedConfigDiagnosticsWithoutPersistingStatus() {
        IntegrationConfig savedConfig = serviceConfig("MYSQL", "mysql-existing-1234");
        savedConfig.setBaseUrl("jdbc:invalid://saved");
        savedConfig.setStatus("CONFIGURED");
        savedConfig.setLastError(null);
        when(integrationConfigMapper.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(savedConfig);

        var result = service.testMysqlConnection(new ServiceIntegrationConfigRequest(
            "jdbc:invalid://submitted",
            "root",
            "****1234",
            "repoguard"
        ));

        assertThat(result.success()).isFalse();
        assertThat(result.testedConfigSource()).isEqualTo("submitted_config");
        assertThat(result.runtimeHealthy()).isNull();
        assertThat(result.runtimeConnectionStatus()).isEqualTo("unavailable");
        assertThat(result.savedConfigHealthy()).isTrue();
        assertThat(result.savedConfigStatus()).isEqualTo("configured");
        assertThat(result.mismatch()).isNull();
        verify(integrationConfigMapper, org.mockito.Mockito.never()).updateById(org.mockito.ArgumentMatchers.any(IntegrationConfig.class));
    }

    @Test
    void testMysqlConnectionReportsRuntimeConfigWhenSavedConfigIsMissing() {
        when(integrationConfigMapper.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(null);

        var result = service.testMysqlConnection(null);

        assertThat(result.success()).isFalse();
        assertThat(result.testedConfigSource()).isEqualTo("runtime_config");
        assertThat(result.runtimeHealthy()).isNull();
        assertThat(result.savedConfigHealthy()).isNull();
        assertThat(result.savedConfigStatus()).isEqualTo("not_configured");
        assertThat(result.runtimeConnectionStatus()).isEqualTo("unavailable");
        assertThat(result.message()).contains("Runtime DataSource");
    }

    @Test
    void getReviewRulesReturnsRulesAndMetricsFromDatabase() {
        when(reviewRuleConfigMapper.selectList(any())).thenReturn(List.of(
            rule("RG-JAVA-001", "异常捕获过宽", "MEDIUM", "ENABLED", 88),
            rule("RG-SECRET-001", "硬编码密钥检测", "HIGH", "DISABLED", 96)
        ));
        when(reviewFindingMapper.selectReviewRuleHitCounts()).thenReturn(List.of(
            ruleHitCount("RG-JAVA-001", 2L),
            ruleHitCount("RG-SECRET-001", 1L)
        ));
        when(reviewFindingMapper.selectReviewRuleFeedbackStat()).thenReturn(ruleFeedbackStat(3L, 1L, 1L, 2L));

        var result = service.getReviewRules();

        assertThat(result.rules()).hasSize(2);
        assertThat(result.rules().getFirst().id()).isEqualTo("RG-JAVA-001");
        assertThat(result.rules().getFirst().status()).isEqualTo("enabled");
        assertThat(result.rules().getFirst().hitCount()).isEqualTo(2);
        assertThat(result.rules().getFirst().applicableLanguages()).isEqualTo("Java");
        assertThat(result.rules().getFirst().filePatterns()).isEqualTo("*.java");
        assertThat(result.rules().getFirst().falsePositiveGuidance()).contains("false positive");
        assertThat(result.metrics()).hasSize(6);
        assertThat(result.metrics().get(4).value()).isEqualTo("50%");
        assertThat(result.metrics().get(5).value()).isEqualTo("50%");
        assertThat(result.metrics()).extracting("label").contains("启用规则", "累计命中");
    }

    @Test
    void updateReviewRuleStatusPersistsNormalizedStatus() {
        ReviewRuleConfig rule = rule("RG-JAVA-001", "异常捕获过宽", "MEDIUM", "ENABLED", 88);
        when(reviewRuleConfigMapper.selectById("RG-JAVA-001")).thenReturn(rule);
        when(reviewFindingMapper.selectReviewRuleHitCounts()).thenReturn(List.of());

        var result = service.updateReviewRuleStatus("rg-java-001", "disabled");

        assertThat(rule.getStatus()).isEqualTo("DISABLED");
        assertThat(result.status()).isEqualTo("disabled");
        verify(reviewRuleConfigMapper).updateById(rule);
    }

    private IntegrationConfig githubConfig(String token) {
        IntegrationConfig config = new IntegrationConfig();
        config.setId(1L);
        config.setProvider("GITHUB");
        config.setStatus("CONFIGURED");
        config.setBaseUrl("https://api.github.com");
        config.setTokenValue(token);
        config.setCreatedAt(LocalDateTime.now());
        config.setUpdatedAt(LocalDateTime.now());
        return config;
    }

    private IntegrationConfig serviceConfig(String provider, String secret) {
        IntegrationConfig config = new IntegrationConfig();
        config.setId(2L);
        config.setProvider(provider);
        config.setStatus("CONFIGURED");
        config.setBaseUrl("MYSQL".equals(provider) ? "jdbc:mysql://localhost:3306/repoguard" : "amqp://localhost:5672");
        config.setDefaultOwner("repoguard");
        config.setDefaultRepo("MYSQL".equals(provider) ? "repoguard" : "/");
        config.setTokenValue(secret);
        config.setCreatedAt(LocalDateTime.now());
        config.setUpdatedAt(LocalDateTime.now());
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
        config.setCreatedAt(LocalDateTime.now());
        config.setUpdatedAt(LocalDateTime.now());
        return config;
    }

    private SystemSettingsConfig systemSettingsConfig() {
        SystemSettingsConfig config = new SystemSettingsConfig();
        config.setId(1L);
        config.setSystemName("RepoGuard Agent");
        config.setLanguage("中文");
        config.setTimezone("Asia/Shanghai");
        config.setRetentionDays(90);
        config.setMaxDiffLines(800);
        config.setAutoComment(true);
        config.setAutoRetry(true);
        config.setGithubComment(true);
        config.setHighRiskPr(true);
        config.setFailedTask(true);
        config.setNotificationEmail("ops@repoguard.dev");
        config.setWebhookSignature(true);
        config.setSecretMasking(true);
        config.setPublicRepoAllowed(false);
        config.setTokenTtlDays(30);
        config.setCreatedAt(LocalDateTime.now());
        config.setUpdatedAt(LocalDateTime.now());
        return config;
    }

    private SystemSettingLog settingLog() {
        SystemSettingLog log = new SystemSettingLog();
        log.setId(1L);
        log.setOperator("admin");
        log.setAction("更新系统设置");
        log.setStatus("成功");
        log.setCreatedAt(LocalDateTime.of(2026, 6, 9, 12, 0));
        return log;
    }

    private ReviewRuleConfig rule(String id, String name, String severity, String status, int confidence) {
        ReviewRuleConfig rule = new ReviewRuleConfig();
        rule.setId(id);
        rule.setRuleName(name);
        rule.setScope("Java Patch");
        rule.setApplicableLanguages("Java");
        rule.setFilePatterns("*.java");
        rule.setSeverity(severity);
        rule.setStatus(status);
        rule.setConfidence(confidence);
        rule.setDescription(name + " description");
        rule.setPositiveExample("catch (IOException ex)");
        rule.setFalsePositiveGuidance("Mark as false positive for framework boundaries.");
        rule.setSortOrder(10);
        rule.setCreatedAt(LocalDateTime.now());
        rule.setUpdatedAt(LocalDateTime.of(2026, 6, 9, 12, 0));
        return rule;
    }

    private ReviewRuleHitCount ruleHitCount(String ruleId, Long total) {
        ReviewRuleHitCount count = new ReviewRuleHitCount();
        count.setRuleId(ruleId);
        count.setTotal(total);
        return count;
    }

    private ReviewRuleFeedbackStat ruleFeedbackStat(Long totalHits, Long validCount, Long falsePositiveCount, Long reviewedCount) {
        ReviewRuleFeedbackStat stat = new ReviewRuleFeedbackStat();
        stat.setTotalHits(totalHits);
        stat.setValidCount(validCount);
        stat.setFalsePositiveCount(falsePositiveCount);
        stat.setReviewedCount(reviewedCount);
        return stat;
    }

    private ProbeServer startLlmProbeServer(String responseBody) throws IOException {
        return startProbeServer("/chat/completions", 200, responseBody);
    }

    private ProbeServer startProbeServer(String path, int statusCode, String responseBody) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> requestBody = new AtomicReference<>("");
        AtomicReference<String> authorization = new AtomicReference<>("");
        server.createContext(path, exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusCode, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return new ProbeServer(
            server,
            "http://127.0.0.1:" + server.getAddress().getPort(),
            requestBody,
            authorization
        );
    }

    private LlmConnectionProbeResponseParser responseParser() {
        return new LlmConnectionProbeResponseParser(objectMapper, reviewResultParser());
    }

    private LlmReviewResultParser reviewResultParser() {
        return new LlmReviewResultParser(
            objectMapper,
            new LlmReviewJsonExtractor(),
            new LlmReviewSchemaRepairer(objectMapper),
            new LlmReviewFindingMapper(),
            new LlmReviewParseFailureSummarizer()
        );
    }

    private record ProbeServer(
        HttpServer server,
        String baseUrl,
        AtomicReference<String> requestBodyRef,
        AtomicReference<String> authorizationRef
    ) implements AutoCloseable {
        @Override
        public void close() {
            server.stop(0);
        }

        String requestBody() {
            return requestBodyRef.get();
        }

        String authorization() {
            return authorizationRef.get();
        }
    }
}
