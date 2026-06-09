package com.repoguard.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.dto.ConnectionTestResultDto;
import com.repoguard.agent.dto.GithubIntegrationConfigDto;
import com.repoguard.agent.dto.GithubIntegrationConfigRequest;
import com.repoguard.agent.dto.ReviewPolicyConfigDto;
import com.repoguard.agent.dto.ReviewPolicyConfigRequest;
import com.repoguard.agent.entity.IntegrationConfig;
import com.repoguard.agent.entity.ReviewPolicyConfig;
import com.repoguard.agent.mapper.IntegrationConfigMapper;
import com.repoguard.agent.mapper.ReviewPolicyConfigMapper;
import com.repoguard.agent.review.LlmReviewResultParser;
import com.repoguard.agent.security.SecretCryptoService;
import com.repoguard.agent.service.SystemConfigService;
import java.math.BigDecimal;
import java.sql.Connection;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class SystemConfigServiceImpl implements SystemConfigService {

    private static final String GITHUB_PROVIDER = "GITHUB";
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MIN_LLM_TEST_MAX_TOKENS = 512;
    private static final int MAX_LLM_TEST_MAX_TOKENS = 4096;

    private final IntegrationConfigMapper integrationConfigMapper;
    private final ReviewPolicyConfigMapper reviewPolicyConfigMapper;
    private final RestClient.Builder restClientBuilder;
    private final ObjectMapper objectMapper;
    private final LlmReviewResultParser llmReviewResultParser;
    private final DataSource dataSource;
    private final RabbitTemplate rabbitTemplate;
    private final SecretCryptoService secretCryptoService;

    public SystemConfigServiceImpl(
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
        this.objectMapper = objectMapper;
        this.llmReviewResultParser = new LlmReviewResultParser(objectMapper);
        this.dataSource = dataSource;
        this.rabbitTemplate = rabbitTemplate;
        this.secretCryptoService = secretCryptoService;
    }

    @Override
    public GithubIntegrationConfigDto getGithubIntegration() {
        return toGithubDto(loadGithubConfig());
    }

    @Override
    @Transactional
    public GithubIntegrationConfigDto updateGithubIntegration(GithubIntegrationConfigRequest request) {
        IntegrationConfig config = loadGithubConfig();
        String token = resolveSecretValue(secretCryptoService.decrypt(config.getTokenValue()), request.token());
        config.setBaseUrl(request.baseUrl().trim());
        config.setTokenValue(secretCryptoService.encrypt(token));
        config.setDefaultOwner(trimToNull(request.defaultOwner()));
        config.setDefaultRepo(trimToNull(request.defaultRepo()));
        config.setStatus(StringUtils.hasText(token) ? "CONFIGURED" : "NOT_CONFIGURED");
        config.setLastError(null);
        config.setUpdatedAt(LocalDateTime.now());
        integrationConfigMapper.updateById(config);
        if (config.getTokenValue() == null) {
            integrationConfigMapper.update(
                new UpdateWrapper<IntegrationConfig>()
                    .eq("id", config.getId())
                    .set("token_value", null)
            );
        }
        integrationConfigMapper.update(
            new UpdateWrapper<IntegrationConfig>()
                .eq("id", config.getId())
                .set("last_error", null)
        );
        return toGithubDto(config);
    }

    @Override
    public ReviewPolicyConfigDto getReviewPolicy() {
        return toReviewPolicyDto(loadReviewPolicy());
    }

    @Override
    @Transactional
    public ReviewPolicyConfigDto updateReviewPolicy(ReviewPolicyConfigRequest request) {
        ReviewPolicyConfig config = loadReviewPolicy();
        String apiKey = resolveSecretValue(secretCryptoService.decrypt(config.getApiKeyValue()), request.apiKey());
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
        config.setUpdatedAt(LocalDateTime.now());
        reviewPolicyConfigMapper.updateById(config);
        if (config.getApiKeyValue() == null) {
            reviewPolicyConfigMapper.update(
                new UpdateWrapper<ReviewPolicyConfig>()
                    .eq("id", config.getId())
                    .set("api_key_value", null)
            );
        }
        return toReviewPolicyDto(config);
    }

    @Override
    @Transactional
    public ConnectionTestResultDto testGithubIntegration() {
        IntegrationConfig config = findGithubConfig();
        if (config == null) {
            return connectionResult(false, "failed", "GitHub integration is not configured");
        }
        try {
            String url = buildGithubTestUrl(config);
            String token = secretCryptoService.decrypt(config.getTokenValue());
            RestClient.RequestHeadersSpec<?> request = restClientBuilder.build()
                .get()
                .uri(url)
                .accept(MediaType.APPLICATION_JSON);
            if (StringUtils.hasText(token)) {
                request.header("Authorization", "Bearer " + token.trim());
            }
            request.header("X-GitHub-Api-Version", "2022-11-28").retrieve().toBodilessEntity();
            return connectionResult(true, "connected", "GitHub connection test succeeded");
        } catch (RuntimeException ex) {
            return connectionResult(false, "failed", conciseError(ex));
        }
    }

    @Override
    public ConnectionTestResultDto testReviewPolicy() {
        ReviewPolicyConfig config = findReviewPolicy();
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
            String content = extractLlmMessageContent(response);
            if (!StringUtils.hasText(content)) {
                return connectionResult(false, "failed", "LLM response did not include usable review content");
            }
            try {
                llmReviewResultParser.parse(content);
            } catch (RuntimeException ex) {
                return connectionResult(false, "failed", "LLM response was received but could not be parsed as review JSON: " + conciseError(ex));
            }
            return connectionResult(true, "connected", "LLM connection test succeeded");
        } catch (Exception ex) {
            return connectionResult(false, "failed", conciseError(ex));
        }
    }

    @Override
    public ConnectionTestResultDto testMysqlConnection() {
        try (Connection connection = dataSource.getConnection()) {
            boolean valid = connection.isValid(2);
            return valid
                ? connectionResult(true, "connected", "MySQL connection test succeeded")
                : connectionResult(false, "failed", "MySQL connection is not valid");
        } catch (Exception ex) {
            return connectionResult(false, "failed", conciseError(ex));
        }
    }

    @Override
    public ConnectionTestResultDto testRabbitMqConnection() {
        try {
            Boolean open = rabbitTemplate.execute(channel -> channel.isOpen());
            return Boolean.TRUE.equals(open)
                ? connectionResult(true, "connected", "RabbitMQ connection test succeeded")
                : connectionResult(false, "failed", "RabbitMQ channel is not open");
        } catch (RuntimeException ex) {
            return connectionResult(false, "failed", conciseError(ex));
        }
    }

    private IntegrationConfig loadGithubConfig() {
        IntegrationConfig config = findGithubConfig();
        if (config != null) {
            return config;
        }

        LocalDateTime now = LocalDateTime.now();
        IntegrationConfig defaultConfig = new IntegrationConfig();
        defaultConfig.setProvider(GITHUB_PROVIDER);
        defaultConfig.setStatus("NOT_CONFIGURED");
        defaultConfig.setBaseUrl("https://api.github.com");
        defaultConfig.setCreatedAt(now);
        defaultConfig.setUpdatedAt(now);
        integrationConfigMapper.insert(defaultConfig);
        return defaultConfig;
    }

    private IntegrationConfig findGithubConfig() {
        return integrationConfigMapper.selectOne(
            new LambdaQueryWrapper<IntegrationConfig>().eq(IntegrationConfig::getProvider, GITHUB_PROVIDER)
        );
    }

    private ReviewPolicyConfig loadReviewPolicy() {
        ReviewPolicyConfig config = findReviewPolicy();
        if (config != null) {
            return config;
        }

        LocalDateTime now = LocalDateTime.now();
        ReviewPolicyConfig defaultConfig = new ReviewPolicyConfig();
        defaultConfig.setId(1L);
        defaultConfig.setLlmEnabled(true);
        defaultConfig.setLlmProvider("dashscope");
        defaultConfig.setModelName("qwen-plus");
        defaultConfig.setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        defaultConfig.setTimeoutSeconds(60);
        defaultConfig.setTemperature(java.math.BigDecimal.valueOf(0.20));
        defaultConfig.setMaxTokens(4096);
        defaultConfig.setFallbackToRules(true);
        defaultConfig.setWorkerConcurrency(1);
        defaultConfig.setCreatedAt(now);
        defaultConfig.setUpdatedAt(now);
        reviewPolicyConfigMapper.insert(defaultConfig);
        return defaultConfig;
    }

    private ReviewPolicyConfig findReviewPolicy() {
        return reviewPolicyConfigMapper.selectById(1L);
    }

    private String buildGithubTestUrl(IntegrationConfig config) {
        String baseUrl = StringUtils.hasText(config.getBaseUrl()) ? config.getBaseUrl().trim() : "https://api.github.com";
        if (StringUtils.hasText(config.getDefaultOwner()) && StringUtils.hasText(config.getDefaultRepo())) {
            return UriComponentsBuilder
                .fromUriString(baseUrl)
                .path("/repos/{owner}/{repo}")
                .build(config.getDefaultOwner().trim(), config.getDefaultRepo().trim())
                .toString();
        }
        return UriComponentsBuilder.fromUriString(baseUrl).path("/rate_limit").toUriString();
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

    private String extractLlmMessageContent(String response) throws Exception {
        JsonNode root = objectMapper.readTree(response == null ? "" : response);
        for (String pointer : List.of(
            "/choices/0/message/content",
            "/choices/0/text",
            "/output_text",
            "/output/0/content/0/text",
            "/content"
        )) {
            String content = nodeText(root.at(pointer));
            if (StringUtils.hasText(content)) {
                return content.trim();
            }
        }
        return "";
    }

    private String nodeText(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isArray()) {
            StringBuilder builder = new StringBuilder();
            for (JsonNode item : node) {
                String text = nodeText(item);
                if (StringUtils.hasText(text)) {
                    if (builder.length() > 0) {
                        builder.append('\n');
                    }
                    builder.append(text.trim());
                }
            }
            return builder.toString();
        }
        if (node.isObject()) {
            for (String field : List.of("text", "content")) {
                String text = nodeText(node.path(field));
                if (StringUtils.hasText(text)) {
                    return text;
                }
            }
        }
        return "";
    }

    private ConnectionTestResultDto connectionResult(boolean success, String status, String message) {
        return new ConnectionTestResultDto(success, status, message, format(LocalDateTime.now()));
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

    private GithubIntegrationConfigDto toGithubDto(IntegrationConfig config) {
        return new GithubIntegrationConfigDto(
            config.getProvider(),
            lower(config.getStatus()),
            config.getBaseUrl(),
            maskSecret(secretCryptoService.decrypt(config.getTokenValue())),
            config.getDefaultOwner(),
            config.getDefaultRepo(),
            format(config.getLastCheckedAt()),
            config.getLastError(),
            format(config.getUpdatedAt())
        );
    }

    private ReviewPolicyConfigDto toReviewPolicyDto(ReviewPolicyConfig config) {
        return new ReviewPolicyConfigDto(
            config.getLlmEnabled(),
            config.getLlmProvider(),
            config.getModelName(),
            config.getBaseUrl(),
            maskSecret(secretCryptoService.decrypt(config.getApiKeyValue())),
            config.getTimeoutSeconds(),
            config.getTemperature(),
            config.getMaxTokens(),
            config.getFallbackToRules(),
            config.getWorkerConcurrency(),
            format(config.getUpdatedAt())
        );
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

    private String maskSecret(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        int visible = Math.min(4, trimmed.length());
        return "****" + trimmed.substring(trimmed.length() - visible);
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String lower(String value) {
        return value == null ? null : value.toLowerCase();
    }

    private String format(LocalDateTime time) {
        return time == null ? null : time.format(DATE_TIME_FORMATTER);
    }
}
