package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.entity.ReviewPolicyConfig;
import com.repoguard.agent.review.LlmConnectionProbeResponseParser;
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

class LlmConnectionProbeTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SecretCryptoService secretCryptoService = new SecretCryptoService("test-encryption-key");
    private final LlmConnectionProbe probe = new LlmConnectionProbe(
        RestClient.builder(),
        new LlmConnectionProbeResponseParser(objectMapper),
        secretCryptoService
    );

    @Test
    void providerReturnsLlmProviderCode() {
        assertThat(probe.provider()).isEqualTo("LLM");
    }

    @Test
    void probeParsesChatCompletionSmokeResponse() throws Exception {
        String llmResponse = """
            {"choices":[{"message":{"content":"{\\"riskLevel\\":\\"INFO\\",\\"findings\\":[]}"}}]}
            """;
        try (ProbeServer server = startProbeServer(llmResponse)) {
            ReviewPolicyConfig config = reviewPolicyConfig("sk-test-1234");
            config.setBaseUrl(server.baseUrl());
            config.setMaxTokens(64);

            ConnectionProbeResult result = probe.probe(config);

            assertThat(result.healthy()).isTrue();
            assertThat(result.status()).isEqualTo("connected");
            assertThat(server.authorization()).isEqualTo("Bearer sk-test-1234");
            JsonNode request = objectMapper.readTree(server.requestBody());
            assertThat(request.path("model").asText()).isEqualTo("qwen-plus");
            assertThat(request.path("max_tokens").asInt()).isGreaterThanOrEqualTo(512);
            assertThat(request.at("/messages/1/content").asText()).contains("riskLevel");
        }
    }

    @Test
    void probeParsesOctetStreamChatCompletionResponse() throws Exception {
        String llmResponse = """
            {"choices":[{"message":{"content":"{\\"riskLevel\\":\\"INFO\\",\\"findings\\":[]}"}}]}
            """;
        try (ProbeServer server = startProbeServer(llmResponse, "application/octet-stream")) {
            ReviewPolicyConfig config = reviewPolicyConfig("sk-test-1234");
            config.setBaseUrl(server.baseUrl());

            ConnectionProbeResult result = probe.probe(config);

            assertThat(result.healthy()).isTrue();
            assertThat(result.status()).isEqualTo("connected");
        }
    }

    @Test
    void probeReportsMalformedReviewJson() throws Exception {
        String llmResponse = """
            {"choices":[{"message":{"content":"OK"}}]}
            """;
        try (ProbeServer server = startProbeServer(llmResponse)) {
            ReviewPolicyConfig config = reviewPolicyConfig("sk-test-1234");
            config.setBaseUrl(server.baseUrl());

            ConnectionProbeResult result = probe.probe(config);

            assertThat(result.healthy()).isFalse();
            assertThat(result.status()).isEqualTo("failed");
            assertThat(result.message()).contains("could not be parsed as review JSON");
        }
    }

    private ReviewPolicyConfig reviewPolicyConfig(String apiKey) {
        ReviewPolicyConfig config = new ReviewPolicyConfig();
        config.setId(1L);
        config.setLlmEnabled(true);
        config.setLlmProvider("dashscope");
        config.setModelName("qwen-plus");
        config.setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        config.setApiKeyValue(secretCryptoService.encrypt(apiKey));
        config.setTimeoutSeconds(60);
        config.setTemperature(BigDecimal.valueOf(0.20));
        config.setMaxTokens(4096);
        config.setFallbackToRules(true);
        config.setWorkerConcurrency(1);
        config.setCreatedAt(LocalDateTime.now());
        config.setUpdatedAt(LocalDateTime.now());
        return config;
    }

    private ProbeServer startProbeServer(String responseBody) throws IOException {
        return startProbeServer(responseBody, "application/json");
    }

    private ProbeServer startProbeServer(String responseBody, String contentType) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> requestBody = new AtomicReference<>("");
        AtomicReference<String> authorization = new AtomicReference<>("");
        server.createContext("/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(200, bytes.length);
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
