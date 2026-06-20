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
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@Service
public class ConnectionTestServiceImpl implements ConnectionTestService {

    private static final String GITHUB_PROVIDER = "GITHUB";
    private static final String MYSQL_PROVIDER = "MYSQL";
    private static final String RABBITMQ_PROVIDER = "RABBITMQ";
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MIN_LLM_TEST_MAX_TOKENS = 512;
    private static final int MAX_LLM_TEST_MAX_TOKENS = 4096;

    private final IntegrationConfigMapper integrationConfigMapper;
    private final ReviewPolicyConfigMapper reviewPolicyConfigMapper;
    private final RestClient.Builder restClientBuilder;
    private final GithubConnectionProbe githubConnectionProbe;
    private final MysqlConnectionProbe mysqlConnectionProbe;
    private final RabbitMqConnectionProbe rabbitMqConnectionProbe;
    private final LlmConnectionProbeResponseParser llmConnectionProbeResponseParser;
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
        this.restClientBuilder = restClientBuilder;
        this.githubConnectionProbe = new GithubConnectionProbe(restClientBuilder, secretCryptoService);
        this.mysqlConnectionProbe = new MysqlConnectionProbe(dataSource, secretCryptoService);
        this.rabbitMqConnectionProbe = new RabbitMqConnectionProbe(rabbitTemplate, secretCryptoService);
        this.llmConnectionProbeResponseParser = new LlmConnectionProbeResponseParser(objectMapper);
        this.secretCryptoService = secretCryptoService;
    }

    @Override
    public ConnectionTestResultDto testGithubIntegration(GithubIntegrationConfigRequest configRequest) {
        IntegrationConfig savedConfig = findGithubConfig();
        boolean transientConfig = configRequest != null;
        IntegrationConfig config = transientConfig ? githubIntegrationForTest(configRequest, savedConfig) : savedConfig;
        if (config == null) {
            return connectionResult(false, "failed", "GitHub integration is not configured");
        }
        try {
            githubConnectionProbe.probe(config);
            if (!transientConfig) {
                markGithubIntegrationChecked(config, null);
            }
            return connectionResult(true, "connected", "GitHub connection test succeeded");
        } catch (RuntimeException ex) {
            String error = conciseError(ex);
            if (!transientConfig) {
                markGithubIntegrationChecked(config, error);
            }
            return connectionResult(false, "failed", error);
        }
    }

    @Override
    public ConnectionTestResultDto testReviewPolicy(ReviewPolicyConfigRequest configRequest) {
        ReviewPolicyConfig savedConfig = findReviewPolicy();
        ReviewPolicyConfig config = configRequest == null ? savedConfig : reviewPolicyForTest(configRequest, savedConfig);
        if (config == null) {
            return connectionResult(false, "failed", "LLM config is not configured");
        }
        if (!Boolean.TRUE.equals(config.getLlmEnabled())) {
            return connectionResult(false, "failed", "LLM review is disabled");
        }
        String apiKey = secretCryptoService.decrypt(config.getApiKeyValue());
        if (!StringUtils.hasText(config.getBaseUrl()) || !StringUtils.hasText(apiKey) || !StringUtils.hasText(config.getModelName())) {
            return connectionResult(false, "failed", "LLM base URL, model or API key is missing");
        }
        try {
            RestClient restClient = restClientBuilder
                .baseUrl(config.getBaseUrl().trim())
                .requestFactory(requestFactory(config.getTimeoutSeconds()))
                .build();
            String response = restClient.post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + apiKey.trim())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(Map.of(
                    "model", config.getModelName(),
                    "temperature", connectionTestTemperature(config.getTemperature()),
                    "max_tokens", connectionTestMaxTokens(config.getMaxTokens()),
                    "messages", List.of(
                        Map.of("role", "system", "content", "You are a RepoGuard connectivity probe. Reply with strict JSON only."),
                        Map.of("role", "user", "content", "Return exactly this JSON object and no markdown: {\"riskLevel\":\"INFO\",\"findings\":[]}")
                    )
                ))
                .retrieve()
                .body(String.class);
            String content = llmConnectionProbeResponseParser.extractReviewContent(response);
            if (!StringUtils.hasText(content)) {
                return connectionResult(false, "failed", "LLM response did not include usable review content");
            }
            try {
                llmConnectionProbeResponseParser.validateReviewJson(content);
            } catch (RuntimeException ex) {
                return connectionResult(false, "failed", "LLM response was received but could not be parsed as review JSON: " + conciseError(ex));
            }
            return connectionResult(true, "connected", "LLM connection test succeeded");
        } catch (Exception ex) {
            return connectionResult(false, "failed", conciseError(ex));
        }
    }

    @Override
    public ConnectionTestResultDto testMysqlConnection(ServiceIntegrationConfigRequest configRequest) {
        IntegrationConfig savedConfig = findServiceIntegration(MYSQL_PROVIDER);
        boolean transientConfig = configRequest != null;
        IntegrationConfig config = transientConfig ? serviceIntegrationForTest(MYSQL_PROVIDER, configRequest, savedConfig) : savedConfig;
        if (config != null) {
            ConnectionProbeResult runtimeProbe = mysqlConnectionProbe.runtimeProbe();
            ConnectionProbeResult configuredProbe = mysqlConnectionProbe.configuredProbe(config);
            boolean success = Boolean.TRUE.equals(configuredProbe.healthy());
            String error = success ? null : configuredProbe.message();
            if (!transientConfig) {
                markServiceIntegrationChecked(config, error);
            }
            String source = transientConfig ? "submitted_config" : "saved_config";
            Boolean savedConfigProbe = transientConfig ? null : success;
            return serviceConnectionResult(
                success,
                success ? "connected" : "failed",
                success ? "MySQL connection test succeeded" : error,
                source,
                runtimeProbe,
                savedConfig,
                savedConfigProbe
            );
        }
        ConnectionProbeResult runtimeProbe = mysqlConnectionProbe.runtimeProbe();
        boolean success = Boolean.TRUE.equals(runtimeProbe.healthy());
        return serviceConnectionResult(
            success,
            success ? "connected" : "failed",
            success ? "MySQL runtime connection test succeeded" : runtimeProbe.message(),
            "runtime_config",
            runtimeProbe,
            savedConfig,
            null
        );
    }

    @Override
    public ConnectionTestResultDto testRabbitMqConnection(ServiceIntegrationConfigRequest configRequest) {
        IntegrationConfig savedConfig = findServiceIntegration(RABBITMQ_PROVIDER);
        boolean transientConfig = configRequest != null;
        IntegrationConfig config = transientConfig ? serviceIntegrationForTest(RABBITMQ_PROVIDER, configRequest, savedConfig) : savedConfig;
        if (config != null) {
            ConnectionProbeResult runtimeProbe = rabbitMqConnectionProbe.runtimeProbe();
            ConnectionProbeResult configuredProbe = rabbitMqConnectionProbe.configuredProbe(config);
            boolean success = Boolean.TRUE.equals(configuredProbe.healthy());
            String error = success ? null : configuredProbe.message();
            if (!transientConfig) {
                markServiceIntegrationChecked(config, error);
            }
            String source = transientConfig ? "submitted_config" : "saved_config";
            Boolean savedConfigProbe = transientConfig ? null : success;
            return serviceConnectionResult(
                success,
                success ? "connected" : "failed",
                success ? "RabbitMQ connection test succeeded" : error,
                source,
                runtimeProbe,
                savedConfig,
                savedConfigProbe
            );
        }
        ConnectionProbeResult runtimeProbe = rabbitMqConnectionProbe.runtimeProbe();
        boolean success = Boolean.TRUE.equals(runtimeProbe.healthy());
        return serviceConnectionResult(
            success,
            success ? "connected" : "failed",
            success ? "RabbitMQ runtime connection test succeeded" : runtimeProbe.message(),
            "runtime_config",
            runtimeProbe,
            savedConfig,
            null
        );
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

    private SimpleClientHttpRequestFactory requestFactory(Integer timeoutSeconds) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds == null ? 60 : timeoutSeconds));
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);
        return requestFactory;
    }

    private BigDecimal connectionTestTemperature(BigDecimal configuredTemperature) {
        return configuredTemperature == null ? BigDecimal.ZERO : configuredTemperature;
    }

    private int connectionTestMaxTokens(Integer configuredMaxTokens) {
        int maxTokens = configuredMaxTokens == null ? MIN_LLM_TEST_MAX_TOKENS : configuredMaxTokens;
        return Math.max(MIN_LLM_TEST_MAX_TOKENS, Math.min(maxTokens, MAX_LLM_TEST_MAX_TOKENS));
    }

    private ConnectionTestResultDto connectionResult(boolean success, String status, String message) {
        return new ConnectionTestResultDto(success, status, message, format(LocalDateTime.now()), null, null, null, null, null, null);
    }

    private ConnectionTestResultDto serviceConnectionResult(
        boolean success,
        String status,
        String message,
        String testedConfigSource,
        ConnectionProbeResult runtimeProbe,
        IntegrationConfig savedConfig,
        Boolean testedSavedConfigHealthy
    ) {
        Boolean runtimeHealthy = runtimeProbe == null ? null : runtimeProbe.healthy();
        Boolean savedConfigHealthy = resolveSavedConfigHealthy(savedConfig, testedSavedConfigHealthy);
        return new ConnectionTestResultDto(
            success,
            status,
            message,
            format(LocalDateTime.now()),
            testedConfigSource,
            runtimeHealthy,
            savedConfigHealthy,
            mismatch(runtimeHealthy, savedConfigHealthy),
            runtimeProbe == null ? null : runtimeProbe.status(),
            savedConfig == null ? "not_configured" : lower(savedConfig.getStatus())
        );
    }

    private Boolean resolveSavedConfigHealthy(IntegrationConfig savedConfig, Boolean testedSavedConfigHealthy) {
        if (savedConfig == null) {
            return null;
        }
        if (testedSavedConfigHealthy != null) {
            return testedSavedConfigHealthy;
        }
        return "CONFIGURED".equals(savedConfig.getStatus()) && !StringUtils.hasText(savedConfig.getLastError());
    }

    private Boolean mismatch(Boolean runtimeHealthy, Boolean savedConfigHealthy) {
        if (runtimeHealthy == null || savedConfigHealthy == null) {
            return null;
        }
        return !runtimeHealthy.equals(savedConfigHealthy);
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

    private String lower(String value) {
        return value == null ? null : value.toLowerCase();
    }

    private String conciseError(Exception ex) {
        String message = ex.getMessage();
        if (!StringUtils.hasText(message) && ex.getCause() != null) {
            message = ex.getCause().getMessage();
        }
        if (!StringUtils.hasText(message)) {
            return ex.getClass().getSimpleName();
        }
        String normalized = message.replaceAll("\\s+", " ").trim();
        return normalized.length() > 240 ? normalized.substring(0, 237) + "..." : normalized;
    }

    private String format(LocalDateTime time) {
        return time == null ? null : time.format(DATE_TIME_FORMATTER);
    }

}
