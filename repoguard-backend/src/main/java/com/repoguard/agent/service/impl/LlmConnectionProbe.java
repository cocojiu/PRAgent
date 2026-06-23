package com.repoguard.agent.service.impl;

import com.repoguard.agent.entity.ReviewPolicyConfig;
import com.repoguard.agent.review.LlmConnectionProbeResponseParser;
import com.repoguard.agent.security.SecretCryptoService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * Executes a lightweight LLM chat-completions probe for review policy connectivity checks.
 */
public class LlmConnectionProbe implements ConnectionProbe<ReviewPolicyConfig> {

    static final String PROVIDER = "LLM";

    private static final int MIN_LLM_TEST_MAX_TOKENS = 512;
    private static final int MAX_LLM_TEST_MAX_TOKENS = 4096;

    private final RestClient.Builder restClientBuilder;
    private final LlmConnectionProbeResponseParser responseParser;
    private final SecretCryptoService secretCryptoService;

    public LlmConnectionProbe(
        RestClient.Builder restClientBuilder,
        LlmConnectionProbeResponseParser responseParser,
        SecretCryptoService secretCryptoService
    ) {
        this.restClientBuilder = restClientBuilder;
        this.responseParser = responseParser;
        this.secretCryptoService = secretCryptoService;
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public ConnectionProbeResult probe(ReviewPolicyConfig config) {
        if (!Boolean.TRUE.equals(config.getLlmEnabled())) {
            return new ConnectionProbeResult(false, "failed", "LLM review is disabled");
        }
        String apiKey = secretCryptoService.decrypt(config.getApiKeyValue());
        if (!StringUtils.hasText(config.getBaseUrl()) || !StringUtils.hasText(apiKey) || !StringUtils.hasText(config.getModelName())) {
            return new ConnectionProbeResult(false, "failed", "LLM base URL, model or API key is missing");
        }
        try {
            byte[] responseBytes = restClientBuilder
                .baseUrl(config.getBaseUrl().trim())
                .requestFactory(requestFactory(config.getTimeoutSeconds()))
                .build()
                .post()
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
                .exchange((request, response) -> response.getBody().readAllBytes());
            String response = responseBytes == null ? "" : new String(responseBytes, StandardCharsets.UTF_8);
            String content = responseParser.extractReviewContent(response);
            if (!StringUtils.hasText(content)) {
                return new ConnectionProbeResult(false, "failed", "LLM response did not include usable review content");
            }
            try {
                responseParser.validateReviewJson(content);
            } catch (RuntimeException ex) {
                return new ConnectionProbeResult(false, "failed", "LLM response was received but could not be parsed as review JSON: " + conciseError(ex));
            }
            return new ConnectionProbeResult(true, "connected", "LLM connection test succeeded");
        } catch (Exception ex) {
            return new ConnectionProbeResult(false, "failed", conciseError(ex));
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
}
