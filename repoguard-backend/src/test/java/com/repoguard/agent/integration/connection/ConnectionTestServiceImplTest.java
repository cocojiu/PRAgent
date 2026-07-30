package com.repoguard.agent.integration.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.dto.GithubIntegrationConfigRequest;
import com.repoguard.agent.dto.ServiceIntegrationConfigRequest;
import com.repoguard.agent.entity.IntegrationConfig;
import com.repoguard.agent.entity.ReviewPolicyConfig;
import com.repoguard.agent.external.ExternalHttpResponseReader;
import com.repoguard.agent.mapper.IntegrationConfigMapper;
import com.repoguard.agent.mapper.ReviewPolicyConfigMapper;
import com.repoguard.agent.review.LlmChatCompletionResponseExtractor;
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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class ConnectionTestServiceImplTest {

    private final IntegrationConfigMapper integrationConfigMapper =
        org.mockito.Mockito.mock(IntegrationConfigMapper.class);
    private final ReviewPolicyConfigMapper reviewPolicyConfigMapper =
        org.mockito.Mockito.mock(ReviewPolicyConfigMapper.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SecretCryptoService secretCryptoService = new SecretCryptoService("test-encryption-key");
    private final ExternalHttpResponseReader responseReader = new ExternalHttpResponseReader();
    private final GithubConnectionProbe githubConnectionProbe =
        new GithubConnectionProbe(RestClient.builder(), secretCryptoService, responseReader);
    private final LlmConnectionProbe llmConnectionProbe =
        new LlmConnectionProbe(RestClient.builder(), responseParser(), secretCryptoService, responseReader);
    private final MysqlConnectionProbe mysqlConnectionProbe = new MysqlConnectionProbe(null, secretCryptoService);
    private final RabbitMqProbeConnectionFactory rabbitMqConnectionFactory =
        new RabbitMqProbeConnectionFactory(secretCryptoService);
    private final RabbitMqConnectionProbe rabbitMqConnectionProbe =
        new RabbitMqConnectionProbe(null, rabbitMqConnectionFactory);
    private final GithubIntegrationConnectionTestRunner githubConnectionTestRunner =
        new GithubIntegrationConnectionTestRunner(githubConnectionProbe);
    private final LlmReviewPolicyConnectionTestRunner llmConnectionTestRunner =
        new LlmReviewPolicyConnectionTestRunner(llmConnectionProbe);
    private final ServiceIntegrationConnectionTestRunner mysqlConnectionTestRunner =
        new ServiceIntegrationConnectionTestRunner(
            "MySQL connection test succeeded",
            "MySQL runtime connection test succeeded",
            mysqlConnectionProbe::runtimeProbe,
            mysqlConnectionProbe
        );
    private final ServiceIntegrationConnectionTestRunner rabbitMqConnectionTestRunner =
        new ServiceIntegrationConnectionTestRunner(
            "RabbitMQ connection test succeeded",
            "RabbitMQ runtime connection test succeeded",
            rabbitMqConnectionProbe::runtimeProbe,
            rabbitMqConnectionProbe
        );
    private final ConnectionTestConfigFactory configFactory = new ConnectionTestConfigFactory(secretCryptoService);
    private final IntegrationConnectionCheckMarker connectionCheckMarker =
        new IntegrationConnectionCheckMarker(integrationConfigMapper);
    private final GithubIntegrationConnectionTestExecutor githubIntegrationConnectionTestExecutor =
        new GithubIntegrationConnectionTestExecutor(integrationConfigMapper, configFactory, connectionCheckMarker);
    private final ReviewPolicyConnectionTestExecutor reviewPolicyConnectionTestExecutor =
        new ReviewPolicyConnectionTestExecutor(reviewPolicyConfigMapper, configFactory);
    private final ServiceIntegrationConnectionTestExecutor serviceIntegrationConnectionTestExecutor =
        new ServiceIntegrationConnectionTestExecutor(integrationConfigMapper, configFactory, connectionCheckMarker);
    private final ConnectionTestServiceImpl service = new ConnectionTestServiceImpl(
        githubConnectionTestRunner,
        githubIntegrationConnectionTestExecutor,
        llmConnectionTestRunner,
        reviewPolicyConnectionTestExecutor,
        mysqlConnectionTestRunner,
        rabbitMqConnectionTestRunner,
        serviceIntegrationConnectionTestExecutor
    );

    @Test
    void constructorRejectsMissingGithubIntegrationConnectionTestExecutor() {
        assertThatThrownBy(() -> new ConnectionTestServiceImpl(
            githubConnectionTestRunner,
            null,
            llmConnectionTestRunner,
            reviewPolicyConnectionTestExecutor,
            mysqlConnectionTestRunner,
            rabbitMqConnectionTestRunner,
            serviceIntegrationConnectionTestExecutor
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("githubIntegrationConnectionTestExecutor");
    }

    @Test
    void constructorRejectsMissingReviewPolicyConnectionTestExecutor() {
        assertThatThrownBy(() -> new ConnectionTestServiceImpl(
            githubConnectionTestRunner,
            githubIntegrationConnectionTestExecutor,
            llmConnectionTestRunner,
            null,
            mysqlConnectionTestRunner,
            rabbitMqConnectionTestRunner,
            serviceIntegrationConnectionTestExecutor
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("reviewPolicyConnectionTestExecutor");
    }

    @Test
    void constructorRejectsMissingServiceIntegrationConnectionTestExecutor() {
        assertThatThrownBy(() -> new ConnectionTestServiceImpl(
            githubConnectionTestRunner,
            githubIntegrationConnectionTestExecutor,
            llmConnectionTestRunner,
            reviewPolicyConnectionTestExecutor,
            mysqlConnectionTestRunner,
            rabbitMqConnectionTestRunner,
            null
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("serviceIntegrationConnectionTestExecutor");
    }

    @Test
    void testGithubIntegrationClearsStaleErrorOnSuccess() throws Exception {
        try (ProbeServer server = startProbeServer("/rate_limit", 200, "{}")) {
            IntegrationConfig config = githubConfig(secretCryptoService.encrypt("ghp_test_1234"));
            config.setBaseUrl(server.baseUrl());
            config.setStatus("FAILED");
            config.setLastError("stale GitHub error");
            when(integrationConfigMapper.selectOne(any())).thenReturn(config);

            var result = service.testGithubIntegration(null);

            assertThat(result.success()).isTrue();
            assertThat(config.getStatus()).isEqualTo("CONFIGURED");
            assertThat(config.getLastError()).isNull();
            assertThat(config.getLastCheckedAt()).isNotNull();
            assertThat(server.authorization()).isEqualTo("Bearer ghp_test_1234");
            verify(integrationConfigMapper).update(isNull(), any());
        }
    }

    @Test
    void testGithubIntegrationUsesCurrentFormConfigWithoutPersistingStatus() throws Exception {
        try (ProbeServer server = startProbeServer("/rate_limit", 200, "{}")) {
            IntegrationConfig savedConfig = githubConfig(secretCryptoService.encrypt("ghp_saved_1234"));
            savedConfig.setBaseUrl("https://api.github.com");
            when(integrationConfigMapper.selectOne(any())).thenReturn(savedConfig);

            var result = service.testGithubIntegration(new GithubIntegrationConfigRequest(
                server.baseUrl(),
                "****1234",
                null,
                null
            ));

            assertThat(result.success()).isTrue();
            assertThat(server.authorization()).isEqualTo("Bearer ghp_saved_1234");
            assertThat(savedConfig.getBaseUrl()).isEqualTo("https://api.github.com");
            verify(integrationConfigMapper, never()).updateById(any(IntegrationConfig.class));
            verify(integrationConfigMapper, never()).update(any());
        }
    }

    @Test
    void testGithubIntegrationWithMaskedBrokenSavedTokenReturnsFailure() throws Exception {
        try (ProbeServer server = startProbeServer("/rate_limit", 200, "{}")) {
            IntegrationConfig savedConfig = githubConfig("enc:v2:other:broken-payload");
            savedConfig.setBaseUrl("https://api.github.com");
            when(integrationConfigMapper.selectOne(any())).thenReturn(savedConfig);

            var result = service.testGithubIntegration(new GithubIntegrationConfigRequest(
                server.baseUrl(),
                "****oken",
                null,
                null
            ));

            assertThat(result.success()).isFalse();
            assertThat(result.status()).isEqualTo("failed");
            assertThat(result.message()).contains("GitHub token is missing or cannot be decrypted");
            assertThat(server.authorization()).isEmpty();
            verify(integrationConfigMapper, never()).updateById(any(IntegrationConfig.class));
            verify(integrationConfigMapper, never()).update(any());
        }
    }

    @Test
    void testGithubIntegrationRecordsLatestErrorOnFailure() throws Exception {
        try (ProbeServer server = startProbeServer("/rate_limit", 500, "{\"message\":\"boom\"}")) {
            IntegrationConfig config = githubConfig(secretCryptoService.encrypt("ghp_test_1234"));
            config.setBaseUrl(server.baseUrl());
            when(integrationConfigMapper.selectOne(any())).thenReturn(config);

            var result = service.testGithubIntegration(null);

            assertThat(result.success()).isFalse();
            assertThat(config.getStatus()).isEqualTo("FAILED");
            assertThat(config.getLastError()).contains("500");
            assertThat(config.getLastCheckedAt()).isNotNull();
            verify(integrationConfigMapper).update(isNull(), any());
        }
    }

    @Test
    void testReviewPolicyParsesChatCompletionSmokeResponse() throws Exception {
        String llmResponse = """
            {"choices":[{"message":{"content":"{\\"riskLevel\\":\\"INFO\\",\\"findings\\":[]}"}}]}
            """;
        try (ProbeServer server = startLlmProbeServer(llmResponse)) {
            ReviewPolicyConfig config = reviewPolicyConfig(secretCryptoService.encrypt("sk-test-1234"));
            config.setBaseUrl(server.baseUrl());
            config.setMaxTokens(64);
            when(reviewPolicyConfigMapper.selectById(1L)).thenReturn(config);

            var result = service.testReviewPolicy(null);

            assertThat(result.success()).isTrue();
            assertThat(result.status()).isEqualTo("connected");
            assertThat(server.authorization()).isEqualTo("Bearer sk-test-1234");
            JsonNode request = objectMapper.readTree(server.requestBody());
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
            ReviewPolicyConfig config = reviewPolicyConfig(secretCryptoService.encrypt("sk-test-1234"));
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
            ReviewPolicyConfig config = reviewPolicyConfig(secretCryptoService.encrypt("sk-test-1234"));
            config.setBaseUrl(server.baseUrl());
            when(reviewPolicyConfigMapper.selectById(1L)).thenReturn(config);

            var result = service.testReviewPolicy(null);

            assertThat(result.success()).isFalse();
            assertThat(result.status()).isEqualTo("failed");
            assertThat(result.message()).contains("could not be parsed as review JSON");
        }
    }

    @Test
    void testReviewPolicyWithBrokenSavedApiKeyReturnsFailureInsteadOfThrowing() {
        ReviewPolicyConfig config = reviewPolicyConfig("enc:v2:local:not-base64");
        when(reviewPolicyConfigMapper.selectById(1L)).thenReturn(config);

        var result = service.testReviewPolicy(null);

        assertThat(result.success()).isFalse();
        assertThat(result.status()).isEqualTo("failed");
        assertThat(result.message()).contains("llm_request_failed");
        assertThat(result.message()).doesNotContain("sk-test-1234");
    }

    @Test
    void testMysqlConnectionReportsSubmittedConfigDiagnosticsWithoutPersistingStatus() {
        IntegrationConfig savedConfig = serviceConfig("MYSQL", secretCryptoService.encrypt("mysql-existing-1234"));
        savedConfig.setBaseUrl("jdbc:invalid://saved");
        savedConfig.setStatus("CONFIGURED");
        savedConfig.setLastError(null);
        when(integrationConfigMapper.selectOne(any())).thenReturn(savedConfig);

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
        verify(integrationConfigMapper, never()).updateById(any(IntegrationConfig.class));
    }

    @Test
    void testMysqlConnectionReportsRuntimeConfigWhenSavedConfigIsMissing() {
        when(integrationConfigMapper.selectOne(any())).thenReturn(null);

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
    void testRabbitMqConnectionReportsRuntimeConfigWhenSavedConfigIsMissing() {
        when(integrationConfigMapper.selectOne(any())).thenReturn(null);

        var result = service.testRabbitMqConnection(null);

        assertThat(result.success()).isFalse();
        assertThat(result.testedConfigSource()).isEqualTo("runtime_config");
        assertThat(result.runtimeHealthy()).isNull();
        assertThat(result.savedConfigHealthy()).isNull();
        assertThat(result.savedConfigStatus()).isEqualTo("not_configured");
        assertThat(result.runtimeConnectionStatus()).isEqualTo("unavailable");
        assertThat(result.message()).contains("Runtime RabbitTemplate");
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
        config.setBaseUrl("jdbc:mysql://localhost:3306/repoguard");
        config.setDefaultOwner("repoguard");
        config.setDefaultRepo("repoguard");
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
        return new LlmConnectionProbeResponseParser(
            new LlmChatCompletionResponseExtractor(objectMapper),
            reviewResultParser()
        );
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
