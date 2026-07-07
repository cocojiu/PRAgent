package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.config.ReviewPolicyProvider;
import com.repoguard.agent.config.ReviewPolicySettings;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.external.ExternalCallException;
import com.repoguard.agent.external.ExternalCallResilience;
import com.repoguard.agent.external.ExternalHttpJsonResponseReader;
import com.repoguard.agent.external.ExternalHttpResponseReader;
import com.repoguard.agent.github.GithubChangedFile;
import com.repoguard.agent.github.GithubPullRequestDiff;
import com.repoguard.agent.observability.RepoGuardMetrics;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class LlmPullRequestReviewerTest {

    @Test
    void constructorRejectsMissingPromptBuilder() {
        assertThatThrownBy(() -> new LlmPullRequestReviewer(
            org.mockito.Mockito.mock(ReviewPolicyProvider.class),
            RestClient.builder(),
            org.mockito.Mockito.mock(RepoGuardMetrics.class),
            org.mockito.Mockito.mock(ExternalCallResilience.class),
            null,
            org.mockito.Mockito.mock(LlmReviewPipeline.class),
            responseReader(),
            responseExtractor()
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("promptBuilder");
    }

    @Test
    void constructorRejectsMissingMetrics() {
        assertThatThrownBy(() -> new LlmPullRequestReviewer(
            org.mockito.Mockito.mock(ReviewPolicyProvider.class),
            RestClient.builder(),
            null,
            org.mockito.Mockito.mock(ExternalCallResilience.class),
            new LlmReviewPromptBuilder(),
            org.mockito.Mockito.mock(LlmReviewPipeline.class),
            responseReader(),
            responseExtractor()
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("metrics");
    }

    @Test
    void constructorRejectsMissingResilience() {
        assertThatThrownBy(() -> new LlmPullRequestReviewer(
            org.mockito.Mockito.mock(ReviewPolicyProvider.class),
            RestClient.builder(),
            org.mockito.Mockito.mock(RepoGuardMetrics.class),
            null,
            new LlmReviewPromptBuilder(),
            org.mockito.Mockito.mock(LlmReviewPipeline.class),
            responseReader(),
            responseExtractor()
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("resilience");
    }

    @Test
    void constructorRejectsMissingResponseReader() {
        assertThatThrownBy(() -> new LlmPullRequestReviewer(
            org.mockito.Mockito.mock(ReviewPolicyProvider.class),
            RestClient.builder(),
            org.mockito.Mockito.mock(RepoGuardMetrics.class),
            org.mockito.Mockito.mock(ExternalCallResilience.class),
            new LlmReviewPromptBuilder(),
            org.mockito.Mockito.mock(LlmReviewPipeline.class),
            null,
            responseExtractor()
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("responseReader");
    }

    @Test
    void constructorRejectsMissingReviewPipeline() {
        assertThatThrownBy(() -> new LlmPullRequestReviewer(
            org.mockito.Mockito.mock(ReviewPolicyProvider.class),
            RestClient.builder(),
            org.mockito.Mockito.mock(RepoGuardMetrics.class),
            org.mockito.Mockito.mock(ExternalCallResilience.class),
            new LlmReviewPromptBuilder(),
            null,
            responseReader(),
            responseExtractor()
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("reviewPipeline");
    }

    @Test
    void constructorRejectsMissingResponseExtractor() {
        assertThatThrownBy(() -> new LlmPullRequestReviewer(
            org.mockito.Mockito.mock(ReviewPolicyProvider.class),
            RestClient.builder(),
            org.mockito.Mockito.mock(RepoGuardMetrics.class),
            org.mockito.Mockito.mock(ExternalCallResilience.class),
            new LlmReviewPromptBuilder(),
            org.mockito.Mockito.mock(LlmReviewPipeline.class),
            responseReader(),
            null
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("responseExtractor");
    }

    @Test
    void reviewFallsBackToRulesWhenLlmCircuitIsOpen() {
        ReviewPolicyProvider reviewPolicyProvider = org.mockito.Mockito.mock(ReviewPolicyProvider.class);
        RuleBasedPullRequestReviewer ruleBasedReviewer = org.mockito.Mockito.mock(RuleBasedPullRequestReviewer.class);
        RepoGuardMetrics metrics = org.mockito.Mockito.mock(RepoGuardMetrics.class);
        ExternalCallResilience resilience = org.mockito.Mockito.mock(ExternalCallResilience.class);
        ReviewPolicySettings settings = llmSettings();
        GithubPullRequestDiff diff = new GithubPullRequestDiff("repo-guard-demo", "spring-boot-demo", 512, List.of());

        when(reviewPolicyProvider.getSettings()).thenReturn(settings);
        when(resilience.llm(eq("chat_completions"), any())).thenThrow(new ExternalCallException(
            "LLM",
            "llm_circuit_open",
            false,
            null,
            "operation=chat_completions",
            null
        ));
        when(ruleBasedReviewer.review(diff)).thenReturn(ReviewResult.completed("LOW", List.of()));

        ReviewResult result = reviewer(
            reviewPolicyProvider,
            ruleBasedReviewer,
            metrics,
            resilience
        ).review(new ReviewTask(), diff);

        assertThat(result.llmStatus()).isEqualTo("FALLBACK");
        assertThat(result.statusDetail()).contains("llm_circuit_open");
        assertThat(result.riskLevel()).isEqualTo("LOW");
        assertThat(result.llmProvider()).isEqualTo("openai");
        assertThat(result.llmModel()).isEqualTo("gpt-test");
        assertThat(result.llmDurationMs()).isNotNull();
        assertThat(result.llmParseStatus()).isEqualTo("fallback");
        assertThat(result.llmPromptSummary()).contains("files=0");
        verify(metrics).llmFallback("llm_circuit_open");
    }

    @Test
    void reviewSplitsLargeDiffAndAggregatesFindings() {
        ReviewPolicyProvider reviewPolicyProvider = org.mockito.Mockito.mock(ReviewPolicyProvider.class);
        RuleBasedPullRequestReviewer ruleBasedReviewer = org.mockito.Mockito.mock(RuleBasedPullRequestReviewer.class);
        ReviewPolicySettings settings = llmSettings();
        List<GithubPullRequestDiff> reviewedChunks = new ArrayList<>();
        GithubPullRequestDiff diff = new GithubPullRequestDiff("repo-guard-demo", "spring-boot-demo", 512, List.of(
            file("src/main/resources/db/migration/V22__risk.sql", 180, 20),
            file("src/main/java/com/repoguard/agent/security/AuthTokenFilter.java", 140, 30),
            file("src/main/resources/application-prod.yml", 20, 5),
            file(".github/workflows/deploy.yml", 35, 6),
            file("package.json", 12, 3),
            file("src/main/java/com/repoguard/agent/service/A.java", 110, 30),
            file("src/main/java/com/repoguard/agent/service/B.java", 120, 30)
        ));

        when(reviewPolicyProvider.getSettings()).thenReturn(settings);
        when(ruleBasedReviewer.review(diff)).thenReturn(ReviewResult.completed("INFO", List.of()));

        ReviewResult result = new TestableLlmPullRequestReviewer(
            reviewPolicyProvider,
            ruleBasedReviewer,
            reviewedChunks
        ).review(new ReviewTask(), diff);

        assertThat(reviewedChunks).hasSizeGreaterThan(1);
        assertThat(reviewedChunks)
            .allSatisfy(chunk -> assertThat(chunk.files()).hasSizeLessThanOrEqualTo(4));
        assertThat(result.riskLevel()).isEqualTo("HIGH");
        assertThat(result.findings()).hasSize(reviewedChunks.size());
        assertThat(result.llmParseStatus()).isEqualTo("parsed");
        assertThat(result.llmPromptSummary()).contains("chunked=true", "aggregateRisk=HIGH");
        assertThat(result.llmPromptSummary()).contains("rulesApplied=true", "ruleFindings=0");
        assertThat(result.llmPromptTokens()).isEqualTo(reviewedChunks.size() * 100);
        assertThat(result.llmCompletionTokens()).isEqualTo(reviewedChunks.size() * 20);
        assertThat(result.llmTotalTokens()).isEqualTo(reviewedChunks.size() * 120);
        assertThat(result.llmEstimatedCost()).isEqualByComparingTo(BigDecimal.valueOf(reviewedChunks.size() * 80).movePointLeft(6));
    }

    @Test
    void reviewCombinesLlmAndRuleFindingsWhenLlmSucceeds() {
        ReviewPolicyProvider reviewPolicyProvider = org.mockito.Mockito.mock(ReviewPolicyProvider.class);
        RuleBasedPullRequestReviewer ruleBasedReviewer = org.mockito.Mockito.mock(RuleBasedPullRequestReviewer.class);
        ReviewPolicySettings settings = llmSettings(99, 700, 4, 450);
        List<GithubPullRequestDiff> reviewedChunks = new ArrayList<>();
        GithubPullRequestDiff diff = new GithubPullRequestDiff("repo-guard-demo", "spring-boot-demo", 512, List.of(
            file("src/main/java/com/repoguard/agent/service/A.java", 10, 2)
        ));

        when(reviewPolicyProvider.getSettings()).thenReturn(settings);
        when(ruleBasedReviewer.review(diff)).thenReturn(ReviewResult.completed(
            "HIGH",
            List.of(new ReviewFindingResult(
                "HIGH",
                "RULE",
                "RG-JAVA-002",
                "src/main/java/com/repoguard/agent/service/A.java",
                12,
                "Rule finding",
                "Use logger"
            ))
        ));

        ReviewResult result = new TestableLlmPullRequestReviewer(
            reviewPolicyProvider,
            ruleBasedReviewer,
            reviewedChunks
        ).review(new ReviewTask(), diff);

        assertThat(result.llmStatus()).isEqualTo("COMPLETED");
        assertThat(result.riskLevel()).isEqualTo("HIGH");
        assertThat(result.findings()).extracting(ReviewFindingResult::source).containsExactly("LLM", "RULE");
        assertThat(result.llmPromptSummary()).contains("rulesApplied=true", "ruleFindings=1", "mergedFindings=2");
    }

    @Test
    void reviewParsesOctetStreamLlmResponseAsUtf8Json() throws Exception {
        ReviewPolicyProvider reviewPolicyProvider = org.mockito.Mockito.mock(ReviewPolicyProvider.class);
        RuleBasedPullRequestReviewer ruleBasedReviewer = org.mockito.Mockito.mock(RuleBasedPullRequestReviewer.class);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            byte[] response = """
                {
                  "choices": [
                    {
                      "message": {
                        "content": "{\\"riskLevel\\":\\"LOW\\",\\"findings\\":[]}"
                      }
                    }
                  ],
                  "usage": {
                    "prompt_tokens": 321,
                    "completion_tokens": 45,
                    "total_tokens": 366
                  }
                }
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            ReviewPolicySettings settings = llmSettings(
                99,
                700,
                4,
                450,
                "http://127.0.0.1:" + server.getAddress().getPort()
            );
            GithubPullRequestDiff diff = new GithubPullRequestDiff("repo-guard-demo", "spring-boot-demo", 512, List.of(
                file("src/main/java/com/repoguard/agent/service/A.java", 10, 2)
            ));

            when(reviewPolicyProvider.getSettings()).thenReturn(settings);
            when(ruleBasedReviewer.review(diff)).thenReturn(ReviewResult.completed("INFO", List.of()));

            ReviewResult result = reviewer(
                reviewPolicyProvider,
                ruleBasedReviewer,
                null,
                null
            ).review(new ReviewTask(), diff);

            assertThat(result.llmStatus()).isEqualTo("COMPLETED");
            assertThat(result.llmParseStatus()).isEqualTo("parsed");
            assertThat(result.llmPromptTokens()).isEqualTo(321);
            assertThat(result.llmCompletionTokens()).isEqualTo(45);
            assertThat(result.llmTotalTokens()).isEqualTo(366);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void reviewClassifiesHttpErrorBeforeParsingLlmResponse() throws Exception {
        ReviewPolicyProvider reviewPolicyProvider = org.mockito.Mockito.mock(ReviewPolicyProvider.class);
        RuleBasedPullRequestReviewer ruleBasedReviewer = org.mockito.Mockito.mock(RuleBasedPullRequestReviewer.class);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            byte[] response = "{\"error\":\"rate limited\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(429, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            ReviewPolicySettings settings = llmSettings(
                99,
                700,
                4,
                450,
                "http://127.0.0.1:" + server.getAddress().getPort()
            );
            GithubPullRequestDiff diff = new GithubPullRequestDiff("repo-guard-demo", "spring-boot-demo", 512, List.of());

            when(reviewPolicyProvider.getSettings()).thenReturn(settings);
            when(ruleBasedReviewer.review(diff)).thenReturn(ReviewResult.completed("LOW", List.of()));

            ReviewResult result = reviewer(
                reviewPolicyProvider,
                ruleBasedReviewer,
                null,
                null
            ).review(new ReviewTask(), diff);

            assertThat(result.llmStatus()).isEqualTo("FALLBACK");
            assertThat(result.statusDetail()).contains("llm_rate_limited", "status=429");
            assertThat(result.riskLevel()).isEqualTo("LOW");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void reviewClassifiesAuthFailureBeforeParsingResponse() throws Exception {
        ReviewResult result = reviewAgainstHttpStatus(
            401,
            "application/json",
            "{\"error\":\"invalid api_key sk-secret123456789\",\"token\":\"Bearer raw-token-value\"}"
        );

        assertThat(result.llmStatus()).isEqualTo("FALLBACK");
        assertThat(result.statusDetail()).contains("llm_auth_failed", "status=401");
        assertThat(result.statusDetail()).doesNotContain("Unable to parse LLM HTTP response");
        assertThat(result.statusDetail()).doesNotContain("sk-secret123456789", "raw-token-value");
    }

    @Test
    void reviewClassifiesServerFailureWithNonJsonBodyBeforeParsing() throws Exception {
        ReviewResult result = reviewAgainstHttpStatus(
            500,
            "text/html",
            "<html>upstream unavailable</html>"
        );

        assertThat(result.llmStatus()).isEqualTo("FALLBACK");
        assertThat(result.statusDetail()).contains(
            "llm_service_unavailable",
            "status=500",
            "responseBody=<html>upstream unavailable</html>"
        );
    }

    @Test
    void reviewFallsBackOnlyFailedChunksToRules() {
        ReviewPolicyProvider reviewPolicyProvider = org.mockito.Mockito.mock(ReviewPolicyProvider.class);
        RuleBasedPullRequestReviewer ruleBasedReviewer = org.mockito.Mockito.mock(RuleBasedPullRequestReviewer.class);
        ReviewPolicySettings settings = llmSettings();
        List<GithubPullRequestDiff> reviewedChunks = new ArrayList<>();
        GithubPullRequestDiff diff = new GithubPullRequestDiff("repo-guard-demo", "spring-boot-demo", 512, List.of(
            file("src/main/resources/db/migration/V22__risk.sql", 180, 20),
            file("src/main/resources/application-prod.yml", 20, 5),
            file("src/main/java/com/repoguard/agent/service/A.java", 110, 30),
            file("src/main/java/com/repoguard/agent/service/B.java", 120, 30),
            file("src/main/java/com/repoguard/agent/service/C.java", 130, 30),
            file("src/main/java/com/repoguard/agent/service/D.java", 140, 30),
            file("src/main/java/com/repoguard/agent/service/E.java", 150, 30)
        ));

        when(reviewPolicyProvider.getSettings()).thenReturn(settings);
        when(ruleBasedReviewer.review(any(GithubPullRequestDiff.class))).thenAnswer(invocation -> {
            GithubPullRequestDiff reviewedDiff = invocation.getArgument(0);
            String matchedFile = reviewedDiff.files().stream()
                .map(GithubChangedFile::filename)
                .filter(file -> file.contains("service/C.java"))
                .findFirst()
                .orElse(null);
            if (matchedFile != null) {
                return ReviewResult.completed(
                    "MEDIUM",
                    List.of(new ReviewFindingResult(
                        "MEDIUM",
                        "RULE",
                        "RG-CONFIG-001",
                        matchedFile,
                        1,
                        "Config fallback finding",
                        "Review production config"
                    ))
                );
            }
            return ReviewResult.completed("INFO", List.of());
        });

        ReviewResult result = new TestableLlmPullRequestReviewer(
            reviewPolicyProvider,
            ruleBasedReviewer,
            reviewedChunks,
            "service/C.java"
        ).review(new ReviewTask(), diff);

        assertThat(result.llmStatus()).isEqualTo("COMPLETED");
        assertThat(result.llmParseStatus()).isEqualTo("partial_fallback");
        assertThat(result.riskLevel()).isEqualTo("HIGH");
        assertThat(result.findings()).extracting(ReviewFindingResult::source).contains("LLM", "RULE");
        assertThat(result.llmPromptSummary()).contains("chunked=true", "failedChunks=1", "rulesApplied=true");
        assertThat(result.llmPromptTokens()).isEqualTo((reviewedChunks.size() - 1) * 100);
    }

    private ReviewPolicySettings llmSettings() {
        return llmSettings(6, 700, 4, 450);
    }

    private ReviewPolicySettings llmSettings(
        Integer chunkFileThreshold,
        Integer chunkLineThreshold,
        Integer chunkMaxFiles,
        Integer chunkMaxLines
    ) {
        return llmSettings(
            chunkFileThreshold,
            chunkLineThreshold,
            chunkMaxFiles,
            chunkMaxLines,
            "https://llm.example.test"
        );
    }

    private ReviewPolicySettings llmSettings(
        Integer chunkFileThreshold,
        Integer chunkLineThreshold,
        Integer chunkMaxFiles,
        Integer chunkMaxLines,
        String baseUrl
    ) {
        return new ReviewPolicySettings(
            true,
            true,
            "openai",
            "gpt-test",
            baseUrl,
            "llm-key",
            30,
            BigDecimal.valueOf(0.2),
            1024,
            true,
            1,
            chunkFileThreshold,
            chunkLineThreshold,
            chunkMaxFiles,
            chunkMaxLines,
            BigDecimal.valueOf(0.5),
            BigDecimal.valueOf(1.5)
        );
    }

    private GithubChangedFile file(String path, int additions, int deletions) {
        return new GithubChangedFile(path, "modified", additions, deletions, "@@ patch for " + path);
    }

    private LlmPullRequestReviewer reviewer(
        ReviewPolicyProvider reviewPolicyProvider,
        RuleBasedPullRequestReviewer ruleBasedReviewer,
        RepoGuardMetrics metrics,
        ExternalCallResilience resilience
    ) {
        ObjectMapper objectMapper = new ObjectMapper();
        LlmReviewPromptBuilder promptBuilder = new LlmReviewPromptBuilder();
        RepoGuardMetrics effectiveMetrics = metrics == null
            ? org.mockito.Mockito.mock(RepoGuardMetrics.class)
            : metrics;
        ExternalCallResilience effectiveResilience = resilience == null
            ? passthroughResilience()
            : resilience;
        return new LlmPullRequestReviewer(
            reviewPolicyProvider,
            RestClient.builder(),
            effectiveMetrics,
            effectiveResilience,
            promptBuilder,
            pipeline(ruleBasedReviewer, objectMapper, effectiveMetrics, DiffChunkingTestFixtures.chunker(), promptBuilder),
            responseReader(objectMapper),
            responseExtractor(objectMapper)
        );
    }

    private static ExternalCallResilience passthroughResilience() {
        ExternalCallResilience resilience = org.mockito.Mockito.mock(ExternalCallResilience.class);
        when(resilience.llm(any(), any())).thenAnswer(invocation -> {
            java.util.function.Supplier<?> supplier = invocation.getArgument(1);
            return supplier.get();
        });
        return resilience;
    }

    private static ExternalHttpJsonResponseReader responseReader() {
        return responseReader(new ObjectMapper());
    }

    private static ExternalHttpJsonResponseReader responseReader(ObjectMapper objectMapper) {
        return new ExternalHttpJsonResponseReader(objectMapper, new ExternalHttpResponseReader());
    }

    private static LlmChatCompletionResponseExtractor responseExtractor() {
        return responseExtractor(new ObjectMapper());
    }

    private static LlmChatCompletionResponseExtractor responseExtractor(ObjectMapper objectMapper) {
        return new LlmChatCompletionResponseExtractor(objectMapper);
    }

    private static LlmReviewPipeline pipeline(
        RuleBasedPullRequestReviewer ruleBasedReviewer,
        ObjectMapper objectMapper,
        RepoGuardMetrics metrics,
        PullRequestDiffChunker diffChunker,
        LlmReviewPromptBuilder promptBuilder
    ) {
        return new LlmReviewPipeline(
            ruleBasedReviewer,
            promptBuilder,
            new LlmRuleReviewMerger(new RiskLevelRanker()),
            new LlmReviewQualityScorer(),
            new LlmReviewCostEstimator(),
            parser(objectMapper),
            metrics,
            new LlmFallbackReasonClassifier(),
            diffChunker
        );
    }

    private static LlmReviewResultParser parser(ObjectMapper objectMapper) {
        return new LlmReviewResultParser(
            objectMapper,
            new LlmReviewJsonExtractor(),
            new LlmReviewSchemaRepairer(objectMapper),
            new LlmReviewFindingMapper(),
            new LlmReviewParseFailureSummarizer()
        );
    }

    private ReviewResult reviewAgainstHttpStatus(int statusCode, String contentType, String responseBody) throws Exception {
        ReviewPolicyProvider reviewPolicyProvider = org.mockito.Mockito.mock(ReviewPolicyProvider.class);
        RuleBasedPullRequestReviewer ruleBasedReviewer = org.mockito.Mockito.mock(RuleBasedPullRequestReviewer.class);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(statusCode, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            ReviewPolicySettings settings = llmSettings(
                99,
                700,
                4,
                450,
                "http://127.0.0.1:" + server.getAddress().getPort()
            );
            GithubPullRequestDiff diff = new GithubPullRequestDiff("repo-guard-demo", "spring-boot-demo", 512, List.of());

            when(reviewPolicyProvider.getSettings()).thenReturn(settings);
            when(ruleBasedReviewer.review(diff)).thenReturn(ReviewResult.completed("LOW", List.of()));

            return reviewer(
                reviewPolicyProvider,
                ruleBasedReviewer,
                null,
                null
            ).review(new ReviewTask(), diff);
        } finally {
            server.stop(0);
        }
    }

    private static class TestableLlmPullRequestReviewer extends LlmPullRequestReviewer {

        private final List<GithubPullRequestDiff> reviewedChunks;
        private final String failingFilePart;

        TestableLlmPullRequestReviewer(
            ReviewPolicyProvider reviewPolicyProvider,
            RuleBasedPullRequestReviewer ruleBasedReviewer,
            List<GithubPullRequestDiff> reviewedChunks
        ) {
            this(reviewPolicyProvider, ruleBasedReviewer, reviewedChunks, null);
        }

        TestableLlmPullRequestReviewer(
            ReviewPolicyProvider reviewPolicyProvider,
            RuleBasedPullRequestReviewer ruleBasedReviewer,
            List<GithubPullRequestDiff> reviewedChunks,
            String failingFilePart
        ) {
            this(
                reviewPolicyProvider,
                ruleBasedReviewer,
                reviewedChunks,
                failingFilePart,
                org.mockito.Mockito.mock(RepoGuardMetrics.class)
            );
        }

        private TestableLlmPullRequestReviewer(
            ReviewPolicyProvider reviewPolicyProvider,
            RuleBasedPullRequestReviewer ruleBasedReviewer,
            List<GithubPullRequestDiff> reviewedChunks,
            String failingFilePart,
            RepoGuardMetrics metrics
        ) {
            super(
                reviewPolicyProvider,
                RestClient.builder(),
                metrics,
                passthroughResilience(),
                new LlmReviewPromptBuilder(),
                pipeline(
                    ruleBasedReviewer,
                    new ObjectMapper(),
                    metrics,
                    DiffChunkingTestFixtures.chunker(),
                    new LlmReviewPromptBuilder()
                ),
                responseReader(),
                responseExtractor()
            );
            this.reviewedChunks = reviewedChunks;
            this.failingFilePart = failingFilePart;
        }

        @Override
        public LlmCallResult callLlm(ReviewPolicySettings settings, ReviewTask task, GithubPullRequestDiff diff) {
            reviewedChunks.add(diff);
            String firstFile = diff.files().isEmpty() ? "unknown" : diff.files().getFirst().filename();
            boolean shouldFail = failingFilePart != null
                && diff.files().stream().map(GithubChangedFile::filename).anyMatch(file -> file.contains(failingFilePart));
            if (shouldFail) {
                throw new IllegalStateException("chunk llm unavailable");
            }
            String riskLevel = firstFile.contains("security") || firstFile.endsWith(".sql") ? "HIGH" : "LOW";
            String content = """
                {
                  "riskLevel": "%s",
                  "findings": [
                    {
                      "severity": "%s",
                      "filePath": "%s",
                      "lineNumber": 12,
                      "message": "Chunk finding",
                      "recommendation": "Review this chunk"
                    }
                  ]
                }
                """.formatted(riskLevel, riskLevel, firstFile);
            return new LlmCallResult(content, 100, 20, 120);
        }
    }
}
