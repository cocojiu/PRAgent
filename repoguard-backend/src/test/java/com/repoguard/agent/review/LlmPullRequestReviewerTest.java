package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.entity.ReviewPolicyConfig;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.external.ExternalCallException;
import com.repoguard.agent.external.ExternalCallResilience;
import com.repoguard.agent.github.GithubChangedFile;
import com.repoguard.agent.github.GithubPullRequestDiff;
import com.repoguard.agent.mapper.ReviewPolicyConfigMapper;
import com.repoguard.agent.observability.RepoGuardMetrics;
import com.repoguard.agent.security.SecretCryptoService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class LlmPullRequestReviewerTest {

    @Test
    void reviewFallsBackToRulesWhenLlmCircuitIsOpen() {
        ReviewPolicyConfigMapper configMapper = org.mockito.Mockito.mock(ReviewPolicyConfigMapper.class);
        RuleBasedPullRequestReviewer ruleBasedReviewer = org.mockito.Mockito.mock(RuleBasedPullRequestReviewer.class);
        SecretCryptoService secretCryptoService = org.mockito.Mockito.mock(SecretCryptoService.class);
        RepoGuardMetrics metrics = org.mockito.Mockito.mock(RepoGuardMetrics.class);
        ExternalCallResilience resilience = org.mockito.Mockito.mock(ExternalCallResilience.class);
        ReviewPolicyConfig config = llmConfig();
        GithubPullRequestDiff diff = new GithubPullRequestDiff("repo-guard-demo", "spring-boot-demo", 512, List.of());

        when(configMapper.selectById(1L)).thenReturn(config);
        when(secretCryptoService.decrypt("enc:v2:local:key")).thenReturn("llm-key");
        when(resilience.llm(eq("chat_completions"), any())).thenThrow(new ExternalCallException(
            "LLM",
            "llm_circuit_open",
            false,
            null,
            "operation=chat_completions",
            null
        ));
        when(ruleBasedReviewer.review(diff)).thenReturn(ReviewResult.completed("LOW", List.of()));

        ReviewResult result = new LlmPullRequestReviewer(
            configMapper,
            ruleBasedReviewer,
            RestClient.builder(),
            new ObjectMapper(),
            secretCryptoService,
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
        ReviewPolicyConfigMapper configMapper = org.mockito.Mockito.mock(ReviewPolicyConfigMapper.class);
        RuleBasedPullRequestReviewer ruleBasedReviewer = org.mockito.Mockito.mock(RuleBasedPullRequestReviewer.class);
        SecretCryptoService secretCryptoService = org.mockito.Mockito.mock(SecretCryptoService.class);
        ReviewPolicyConfig config = llmConfig();
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

        when(configMapper.selectById(1L)).thenReturn(config);
        when(secretCryptoService.decrypt("enc:v2:local:key")).thenReturn("llm-key");
        when(ruleBasedReviewer.review(diff)).thenReturn(ReviewResult.completed("INFO", List.of()));

        ReviewResult result = new TestableLlmPullRequestReviewer(
            configMapper,
            ruleBasedReviewer,
            secretCryptoService,
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
        ReviewPolicyConfigMapper configMapper = org.mockito.Mockito.mock(ReviewPolicyConfigMapper.class);
        RuleBasedPullRequestReviewer ruleBasedReviewer = org.mockito.Mockito.mock(RuleBasedPullRequestReviewer.class);
        SecretCryptoService secretCryptoService = org.mockito.Mockito.mock(SecretCryptoService.class);
        ReviewPolicyConfig config = llmConfig();
        config.setChunkFileThreshold(99);
        List<GithubPullRequestDiff> reviewedChunks = new ArrayList<>();
        GithubPullRequestDiff diff = new GithubPullRequestDiff("repo-guard-demo", "spring-boot-demo", 512, List.of(
            file("src/main/java/com/repoguard/agent/service/A.java", 10, 2)
        ));

        when(configMapper.selectById(1L)).thenReturn(config);
        when(secretCryptoService.decrypt("enc:v2:local:key")).thenReturn("llm-key");
        when(ruleBasedReviewer.review(diff)).thenReturn(ReviewResult.completed(
            "HIGH",
            List.of(new ReviewFindingResult("HIGH", "RULE", "RG-JAVA-002", "src/main/java/com/repoguard/agent/service/A.java", 12, "Rule finding", "Use logger"))
        ));

        ReviewResult result = new TestableLlmPullRequestReviewer(
            configMapper,
            ruleBasedReviewer,
            secretCryptoService,
            reviewedChunks
        ).review(new ReviewTask(), diff);

        assertThat(result.llmStatus()).isEqualTo("COMPLETED");
        assertThat(result.riskLevel()).isEqualTo("HIGH");
        assertThat(result.findings()).extracting(ReviewFindingResult::source).containsExactly("LLM", "RULE");
        assertThat(result.llmPromptSummary()).contains("rulesApplied=true", "ruleFindings=1", "mergedFindings=2");
    }

    private ReviewPolicyConfig llmConfig() {
        ReviewPolicyConfig config = new ReviewPolicyConfig();
        config.setId(1L);
        config.setLlmEnabled(true);
        config.setLlmProvider("openai");
        config.setBaseUrl("https://llm.example.test");
        config.setApiKeyValue("enc:v2:local:key");
        config.setModelName("gpt-test");
        config.setTemperature(BigDecimal.valueOf(0.2));
        config.setMaxTokens(1024);
        config.setTimeoutSeconds(30);
        config.setFallbackToRules(true);
        config.setChunkFileThreshold(6);
        config.setChunkLineThreshold(700);
        config.setChunkMaxFiles(4);
        config.setChunkMaxLines(450);
        config.setInputTokenPricePerMillion(BigDecimal.valueOf(0.5));
        config.setOutputTokenPricePerMillion(BigDecimal.valueOf(1.5));
        return config;
    }

    private GithubChangedFile file(String path, int additions, int deletions) {
        return new GithubChangedFile(path, "modified", additions, deletions, "@@ patch for " + path);
    }

    private static class TestableLlmPullRequestReviewer extends LlmPullRequestReviewer {

        private final List<GithubPullRequestDiff> reviewedChunks;

        TestableLlmPullRequestReviewer(
            ReviewPolicyConfigMapper reviewPolicyConfigMapper,
            RuleBasedPullRequestReviewer ruleBasedReviewer,
            SecretCryptoService secretCryptoService,
            List<GithubPullRequestDiff> reviewedChunks
        ) {
            super(
                reviewPolicyConfigMapper,
                ruleBasedReviewer,
                RestClient.builder(),
                new ObjectMapper(),
                secretCryptoService,
                null,
                null,
                new PullRequestDiffChunker()
            );
            this.reviewedChunks = reviewedChunks;
        }

        @Override
        protected LlmCallResult callLlm(ReviewPolicyConfig config, ReviewTask task, GithubPullRequestDiff diff) {
            reviewedChunks.add(diff);
            String firstFile = diff.files().isEmpty() ? "unknown" : diff.files().getFirst().filename();
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
