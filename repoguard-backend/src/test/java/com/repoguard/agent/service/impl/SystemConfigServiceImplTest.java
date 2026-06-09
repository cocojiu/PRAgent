package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.dto.GithubIntegrationConfigRequest;
import com.repoguard.agent.dto.ReviewPolicyConfigRequest;
import com.repoguard.agent.entity.IntegrationConfig;
import com.repoguard.agent.entity.ReviewPolicyConfig;
import com.repoguard.agent.mapper.IntegrationConfigMapper;
import com.repoguard.agent.mapper.ReviewPolicyConfigMapper;
import com.repoguard.agent.security.SecretCryptoService;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class SystemConfigServiceImplTest {

    private final IntegrationConfigMapper integrationConfigMapper = org.mockito.Mockito.mock(IntegrationConfigMapper.class);
    private final ReviewPolicyConfigMapper reviewPolicyConfigMapper = org.mockito.Mockito.mock(ReviewPolicyConfigMapper.class);
    private final SecretCryptoService secretCryptoService = new SecretCryptoService("test-encryption-key");
    private final SystemConfigServiceImpl service = new SystemConfigServiceImpl(
        integrationConfigMapper,
        reviewPolicyConfigMapper,
        RestClient.builder(),
        new ObjectMapper(),
        null,
        null,
        secretCryptoService
    );

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

        assertThat(config.getTokenValue()).startsWith("enc:v1:");
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
            2
        ));

        assertThat(config.getApiKeyValue()).startsWith("enc:v1:");
        assertThat(secretCryptoService.decrypt(config.getApiKeyValue())).isEqualTo("sk-existing-5678");
        assertThat(config.getTimeoutSeconds()).isEqualTo(90);
        assertThat(config.getWorkerConcurrency()).isEqualTo(2);
        assertThat(result.apiKey()).isEqualTo("****5678");
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
            2
        ));

        assertThat(config.getApiKeyValue()).isNull();
        assertThat(result.apiKey()).isNull();
        verify(reviewPolicyConfigMapper).updateById(config);
        verify(reviewPolicyConfigMapper).update(any(UpdateWrapper.class));
    }

    @Test
    void testReviewPolicyParsesChatCompletionSmokeResponse() throws Exception {
        String llmResponse = """
            {"choices":[{"message":{"content":"{\\"riskLevel\\":\\"INFO\\",\\"findings\\":[]}"}}]}
            """;
        try (LlmProbeServer server = startLlmProbeServer(llmResponse)) {
            ReviewPolicyConfig config = reviewPolicyConfig("sk-test-1234");
            config.setBaseUrl(server.baseUrl());
            config.setMaxTokens(64);
            when(reviewPolicyConfigMapper.selectById(1L)).thenReturn(config);

            var result = service.testReviewPolicy();

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
        try (LlmProbeServer server = startLlmProbeServer(llmResponse)) {
            ReviewPolicyConfig config = reviewPolicyConfig("sk-test-1234");
            config.setBaseUrl(server.baseUrl());
            when(reviewPolicyConfigMapper.selectById(1L)).thenReturn(config);

            var result = service.testReviewPolicy();

            assertThat(result.success()).isTrue();
            assertThat(result.status()).isEqualTo("connected");
        }
    }

    @Test
    void testReviewPolicyReportsMalformedReviewJson() throws Exception {
        String llmResponse = """
            {"choices":[{"message":{"content":"OK"}}]}
            """;
        try (LlmProbeServer server = startLlmProbeServer(llmResponse)) {
            ReviewPolicyConfig config = reviewPolicyConfig("sk-test-1234");
            config.setBaseUrl(server.baseUrl());
            when(reviewPolicyConfigMapper.selectById(1L)).thenReturn(config);

            var result = service.testReviewPolicy();

            assertThat(result.success()).isFalse();
            assertThat(result.status()).isEqualTo("failed");
            assertThat(result.message()).contains("could not be parsed as review JSON");
        }
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

    private LlmProbeServer startLlmProbeServer(String responseBody) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> requestBody = new AtomicReference<>("");
        AtomicReference<String> authorization = new AtomicReference<>("");
        server.createContext("/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return new LlmProbeServer(
            server,
            "http://127.0.0.1:" + server.getAddress().getPort(),
            requestBody,
            authorization
        );
    }

    private record LlmProbeServer(
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
