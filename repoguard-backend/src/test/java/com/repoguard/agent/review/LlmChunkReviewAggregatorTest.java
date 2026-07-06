package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.config.ReviewPolicySettings;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.github.GithubChangedFile;
import com.repoguard.agent.github.GithubPullRequestDiff;
import com.repoguard.agent.observability.RepoGuardMetrics;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class LlmChunkReviewAggregatorTest {

    private final RuleBasedPullRequestReviewer ruleBasedReviewer = org.mockito.Mockito.mock(
        RuleBasedPullRequestReviewer.class
    );
    private final RepoGuardMetrics metrics = org.mockito.Mockito.mock(RepoGuardMetrics.class);
    private final LlmChunkReviewAggregator aggregator = new LlmChunkReviewAggregator(
        ruleBasedReviewer,
        new LlmReviewPromptBuilder(),
        new LlmRuleReviewMerger(new RiskLevelRanker()),
        new LlmReviewQualityScorer(),
        new LlmReviewCostEstimator(),
        metrics
    );
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LlmReviewResultParser parser = new LlmReviewResultParser(
        objectMapper,
        new LlmReviewJsonExtractor(),
        new LlmReviewSchemaRepairer(objectMapper),
        new LlmReviewFindingMapper(),
        new LlmReviewParseFailureSummarizer()
    );

    @Test
    void aggregatesSuccessfulChunksAndFallsBackOnlyFailedChunksToRules() {
        GithubPullRequestDiff fullDiff = diff("src/A.java", "src/B.java", "src/C.java");
        List<PullRequestDiffChunk> chunks = List.of(
            chunk(1, "src/A.java"),
            chunk(2, "src/B.java"),
            chunk(3, "src/C.java")
        );
        ReviewPolicySettings settings = settings();
        ReviewTask task = new ReviewTask();
        LlmReviewCaller caller = (ignoredSettings, ignoredTask, chunkDiff) -> {
            String path = chunkDiff.files().getFirst().filename();
            if (path.endsWith("B.java")) {
                throw new IllegalStateException("chunk failed");
            }
            return new LlmCallResult(llmJson(path), 100, 25, 125);
        };
        ReviewPipelineContext context = new ReviewPipelineContext(
            task,
            fullDiff,
            settings,
            "promptSummary",
            System.nanoTime(),
            caller
        );
        when(ruleBasedReviewer.review(any(GithubPullRequestDiff.class))).thenReturn(ReviewResult.completed(
            "MEDIUM",
            List.of(new ReviewFindingResult(
                "MEDIUM",
                "RULE",
                "RG-FALLBACK",
                "src/B.java",
                1,
                "Fallback rule finding",
                "Review failed chunk"
            ))
        ));

        ReviewResult result = aggregator.aggregate(context, fullDiff, chunks, parser);

        assertThat(result.riskLevel()).isEqualTo("HIGH");
        assertThat(result.llmParseStatus()).isEqualTo(LlmChunkReviewAggregator.PARTIAL_FALLBACK_STATUS);
        assertThat(result.findings()).extracting(ReviewFindingResult::source).containsExactly("LLM", "RULE", "LLM");
        assertThat(result.llmPromptTokens()).isEqualTo(200);
        assertThat(result.llmCompletionTokens()).isEqualTo(50);
        assertThat(result.llmTotalTokens()).isEqualTo(250);
        assertThat(result.llmEstimatedCost()).isEqualByComparingTo("0.000400");
        assertThat(result.llmPromptSummary()).contains("chunked=true", "failedChunks=1");
        verify(metrics).llmFallback(LlmChunkReviewAggregator.CHUNK_PARTIAL_FAILURE_CATEGORY);
    }

    private GithubPullRequestDiff diff(String... paths) {
        return new GithubPullRequestDiff(
            "octocat",
            "Hello-World",
            1,
            java.util.Arrays.stream(paths).map(this::file).toList()
        );
    }

    private PullRequestDiffChunk chunk(int index, String path) {
        GithubPullRequestDiff diff = diff(path);
        return new PullRequestDiffChunk(index, 3, diff, 1, 1, 0, List.of("test"));
    }

    private GithubChangedFile file(String path) {
        return new GithubChangedFile(path, "modified", 1, 0, "@@ -0,0 +1,1 @@\n+value");
    }

    private String llmJson(String path) {
        return """
            {
              "riskLevel": "HIGH",
              "findings": [
                {
                  "severity": "HIGH",
                  "filePath": "%s",
                  "lineNumber": 1,
                  "message": "Potential issue from chunk review",
                  "recommendation": "Apply a targeted fix"
                }
              ]
            }
            """.formatted(path);
    }

    private ReviewPolicySettings settings() {
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
            6,
            700,
            4,
            450,
            BigDecimal.ONE,
            BigDecimal.valueOf(4)
        );
    }
}
