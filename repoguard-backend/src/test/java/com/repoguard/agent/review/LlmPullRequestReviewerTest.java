package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.repoguard.agent.github.GithubChangedFile;
import com.repoguard.agent.github.GithubPullRequestDiff;
import com.repoguard.agent.observability.RepoGuardMetrics;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class LlmPullRequestReviewerTest {

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

        ReviewResult result = new LlmPullRequestReviewer(
            reviewPolicyProvider,
            ruleBasedReviewer,
            RestClient.builder(),
            new ObjectMapper(),
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
        return new ReviewPolicySettings(
            true,
            true,
            "openai",
            "gpt-test",
            "https://llm.example.test",
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
            super(
                reviewPolicyProvider,
                ruleBasedReviewer,
                RestClient.builder(),
                new ObjectMapper(),
                null,
                null,
                new PullRequestDiffChunker()
            );
            this.reviewedChunks = reviewedChunks;
            this.failingFilePart = failingFilePart;
        }

        @Override
        protected LlmCallResult callLlm(ReviewPolicySettings settings, ReviewTask task, GithubPullRequestDiff diff) {
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
