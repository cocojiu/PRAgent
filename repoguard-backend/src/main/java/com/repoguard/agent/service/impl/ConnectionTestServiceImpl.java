package com.repoguard.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.dto.ConnectionTestResultDto;
import com.repoguard.agent.dto.GithubIntegrationConfigRequest;
import com.repoguard.agent.dto.ReviewPolicyConfigRequest;
import com.repoguard.agent.dto.ServiceIntegrationConfigRequest;
import com.repoguard.agent.entity.IntegrationConfig;
import com.repoguard.agent.entity.ReviewPolicyConfig;
import com.repoguard.agent.mapper.IntegrationConfigMapper;
import com.repoguard.agent.mapper.ReviewPolicyConfigMapper;
import com.repoguard.agent.review.LlmConnectionProbeResponseParser;
import com.repoguard.agent.security.SecretCryptoService;
import com.repoguard.agent.service.ConnectionTestService;
import java.time.LocalDateTime;
import javax.sql.DataSource;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@Service
public class ConnectionTestServiceImpl implements ConnectionTestService {

    private static final String GITHUB_PROVIDER = GithubConnectionProbe.PROVIDER;
    private static final String MYSQL_PROVIDER = MysqlConnectionProbe.PROVIDER;
    private static final String RABBITMQ_PROVIDER = RabbitMqConnectionProbe.PROVIDER;
    private final IntegrationConfigMapper integrationConfigMapper;
    private final ReviewPolicyConfigMapper reviewPolicyConfigMapper;
    private final GithubConnectionProbe githubConnectionProbe;
    private final GithubIntegrationConnectionTestRunner githubConnectionTestRunner;
    private final LlmConnectionProbe llmConnectionProbe;
    private final LlmReviewPolicyConnectionTestRunner llmConnectionTestRunner;
    private final MysqlConnectionProbe mysqlConnectionProbe;
    private final RabbitMqConnectionProbe rabbitMqConnectionProbe;
    private final ServiceIntegrationConnectionTestRunner mysqlConnectionTestRunner;
    private final ServiceIntegrationConnectionTestRunner rabbitMqConnectionTestRunner;
    private final SecretCryptoService secretCryptoService;

    public ConnectionTestServiceImpl(
        IntegrationConfigMapper integrationConfigMapper,
        ReviewPolicyConfigMapper reviewPolicyConfigMapper,
        RestClient.Builder restClientBuilder,
        ObjectMapper objectMapper,
        DataSource dataSource,
        RabbitTemplate rabbitTemplate,
        SecretCryptoService secretCryptoService
    ) {
        this.integrationConfigMapper = integrationConfigMapper;
        this.reviewPolicyConfigMapper = reviewPolicyConfigMapper;
        this.githubConnectionProbe = new GithubConnectionProbe(restClientBuilder, secretCryptoService);
        this.githubConnectionTestRunner = new GithubIntegrationConnectionTestRunner(this.githubConnectionProbe);
        this.llmConnectionProbe = new LlmConnectionProbe(
            restClientBuilder,
            new LlmConnectionProbeResponseParser(objectMapper),
            secretCryptoService
        );
        this.llmConnectionTestRunner = new LlmReviewPolicyConnectionTestRunner(this.llmConnectionProbe);
        this.mysqlConnectionProbe = new MysqlConnectionProbe(dataSource, secretCryptoService);
        this.rabbitMqConnectionProbe = new RabbitMqConnectionProbe(rabbitTemplate, secretCryptoService);
        this.mysqlConnectionTestRunner = new ServiceIntegrationConnectionTestRunner(
            "MySQL connection test succeeded",
            "MySQL runtime connection test succeeded",
            this.mysqlConnectionProbe::runtimeProbe,
            this.mysqlConnectionProbe
        );
        this.rabbitMqConnectionTestRunner = new ServiceIntegrationConnectionTestRunner(
            "RabbitMQ connection test succeeded",
            "RabbitMQ runtime connection test succeeded",
            this.rabbitMqConnectionProbe::runtimeProbe,
            this.rabbitMqConnectionProbe
        );
        this.secretCryptoService = secretCryptoService;
    }

    @Override
    public ConnectionTestResultDto testGithubIntegration(GithubIntegrationConfigRequest configRequest) {
        IntegrationConfig savedConfig = findGithubConfig();
        boolean transientConfig = configRequest != null;
        IntegrationConfig config = transientConfig ? githubIntegrationForTest(configRequest, savedConfig) : savedConfig;
        return githubConnectionTestRunner.run(config, transientConfig, this::markGithubIntegrationChecked);
    }

    @Override
    public ConnectionTestResultDto testReviewPolicy(ReviewPolicyConfigRequest configRequest) {
        ReviewPolicyConfig savedConfig = findReviewPolicy();
        ReviewPolicyConfig config = configRequest == null ? savedConfig : reviewPolicyForTest(configRequest, savedConfig);
        return llmConnectionTestRunner.run(config);
    }

    @Override
    public ConnectionTestResultDto testMysqlConnection(ServiceIntegrationConfigRequest configRequest) {
        IntegrationConfig savedConfig = findServiceIntegration(MYSQL_PROVIDER);
        boolean transientConfig = configRequest != null;
        IntegrationConfig config = transientConfig ? serviceIntegrationForTest(MYSQL_PROVIDER, configRequest, savedConfig) : savedConfig;
        return mysqlConnectionTestRunner.run(savedConfig, config, transientConfig, this::markServiceIntegrationChecked);
    }

    @Override
    public ConnectionTestResultDto testRabbitMqConnection(ServiceIntegrationConfigRequest configRequest) {
        IntegrationConfig savedConfig = findServiceIntegration(RABBITMQ_PROVIDER);
        boolean transientConfig = configRequest != null;
        IntegrationConfig config = transientConfig ? serviceIntegrationForTest(RABBITMQ_PROVIDER, configRequest, savedConfig) : savedConfig;
        return rabbitMqConnectionTestRunner.run(savedConfig, config, transientConfig, this::markServiceIntegrationChecked);
    }

    private IntegrationConfig findGithubConfig() {
        return integrationConfigMapper.selectOne(
            new LambdaQueryWrapper<IntegrationConfig>().eq(IntegrationConfig::getProvider, GITHUB_PROVIDER)
        );
    }

    private IntegrationConfig githubIntegrationForTest(GithubIntegrationConfigRequest request, IntegrationConfig savedConfig) {
        IntegrationConfig config = new IntegrationConfig();
        String token = resolveSecretValue(
            savedConfig == null ? null : secretCryptoService.decrypt(savedConfig.getTokenValue()),
            request.token()
        );
        config.setProvider(GITHUB_PROVIDER);
        config.setStatus("CONFIGURED");
        config.setBaseUrl(request.baseUrl().trim());
        config.setTokenValue(secretCryptoService.encrypt(token));
        config.setDefaultOwner(trimToNull(request.defaultOwner()));
        config.setDefaultRepo(trimToNull(request.defaultRepo()));
        return config;
    }

    private IntegrationConfig findServiceIntegration(String provider) {
        return integrationConfigMapper.selectOne(
            new LambdaQueryWrapper<IntegrationConfig>().eq(IntegrationConfig::getProvider, provider)
        );
    }

    private IntegrationConfig serviceIntegrationForTest(
        String provider,
        ServiceIntegrationConfigRequest request,
        IntegrationConfig savedConfig
    ) {
        IntegrationConfig config = new IntegrationConfig();
        String secret = resolveSecretValue(
            savedConfig == null ? null : secretCryptoService.decrypt(savedConfig.getTokenValue()),
            request.secret()
        );
        config.setProvider(provider);
        config.setStatus("CONFIGURED");
        config.setBaseUrl(request.baseUrl().trim());
        config.setTokenValue(secretCryptoService.encrypt(secret));
        config.setDefaultOwner(trimToNull(request.username()));
        config.setDefaultRepo(trimToNull(request.resource()));
        return config;
    }

    private ReviewPolicyConfig findReviewPolicy() {
        return reviewPolicyConfigMapper.selectById(1L);
    }

    private ReviewPolicyConfig reviewPolicyForTest(ReviewPolicyConfigRequest request, ReviewPolicyConfig savedConfig) {
        ReviewPolicyConfig config = new ReviewPolicyConfig();
        String apiKey = resolveSecretValue(
            savedConfig == null ? null : secretCryptoService.decrypt(savedConfig.getApiKeyValue()),
            request.apiKey()
        );
        config.setLlmEnabled(request.llmEnabled());
        config.setLlmProvider(request.llmProvider().trim());
        config.setModelName(request.modelName().trim());
        config.setBaseUrl(trimToNull(request.baseUrl()));
        config.setApiKeyValue(secretCryptoService.encrypt(apiKey));
        config.setTimeoutSeconds(request.timeoutSeconds());
        config.setTemperature(request.temperature());
        config.setMaxTokens(request.maxTokens());
        config.setFallbackToRules(request.fallbackToRules());
        config.setWorkerConcurrency(request.workerConcurrency());
        config.setChunkFileThreshold(request.chunkFileThreshold());
        config.setChunkLineThreshold(request.chunkLineThreshold());
        config.setChunkMaxFiles(request.chunkMaxFiles());
        config.setChunkMaxLines(request.chunkMaxLines());
        config.setInputTokenPricePerMillion(request.inputTokenPricePerMillion());
        config.setOutputTokenPricePerMillion(request.outputTokenPricePerMillion());
        return config;
    }

    private void markGithubIntegrationChecked(IntegrationConfig config, String error) {
        if (config == null || config.getId() == null) {
            return;
        }
        config.setLastCheckedAt(LocalDateTime.now());
        config.setLastError(error);
        config.setStatus(error == null ? "CONFIGURED" : "FAILED");
        config.setUpdatedAt(LocalDateTime.now());
        integrationConfigMapper.updateById(config);
        if (error == null) {
            integrationConfigMapper.update(
                new UpdateWrapper<IntegrationConfig>()
                    .eq("id", config.getId())
                    .set("last_error", null)
            );
        }
    }

    private void markServiceIntegrationChecked(IntegrationConfig config, String error) {
        if (config == null || config.getId() == null) {
            return;
        }
        config.setLastCheckedAt(LocalDateTime.now());
        config.setLastError(error);
        config.setStatus(error == null ? "CONFIGURED" : "FAILED");
        config.setUpdatedAt(LocalDateTime.now());
        integrationConfigMapper.updateById(config);
        if (error == null) {
            integrationConfigMapper.update(
                new UpdateWrapper<IntegrationConfig>()
                    .eq("id", config.getId())
                    .set("last_error", null)
            );
        }
    }

    private String resolveSecretValue(String currentValue, String submittedValue) {
        if (submittedValue == null) {
            return currentValue;
        }
        String trimmed = submittedValue.trim();
        if (trimmed.startsWith("****")) {
            return currentValue;
        }
        return StringUtils.hasText(trimmed) ? trimmed : null;
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

}
