package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class LlmChunkReviewResultAggregatorTest {

    private final LlmReviewPromptBuilder promptBuilder = new LlmReviewPromptBuilder();
    private final LlmRuleReviewMerger reviewMerger = new LlmRuleReviewMerger(new RiskLevelRanker());
    private final LlmReviewCostEstimator costEstimator = new LlmReviewCostEstimator();
    private final LlmChunkReviewResultAggregator aggregator = new LlmChunkReviewResultAggregator(
        promptBuilder,
        reviewMerger,
        costEstimator
    );

    @Test
    void constructorRejectsMissingAggregationDependencies() {
        assertThatThrownBy(() -> new LlmChunkReviewResultAggregator(null, reviewMerger, costEstimator))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("promptBuilder");
        assertThatThrownBy(() -> new LlmChunkReviewResultAggregator(promptBuilder, null, costEstimator))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("reviewMerger");
        assertThatThrownBy(() -> new LlmChunkReviewResultAggregator(promptBuilder, reviewMerger, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("costEstimator");
    }

    @Test
    void mergesOrderedLlmAndFallbackOutcomesWithTokensCostAndPartialStatus() {
        PullRequestDiff fullDiff = diff("src/A.java", "src/B.java");
        List<PullRequestDiffChunk> chunks = List.of(
            chunk(1, 2, "src/A.java"),
            chunk(2, 2, "src/B.java")
        );
        ReviewResult llmReview = ReviewResult.completed("HIGH", List.of(finding(
            "HIGH",
            "LLM",
            null,
            "src/A.java"
        )));
        ReviewResult fallbackReview = ReviewResult.completed("MEDIUM", List.of(finding(
            "MEDIUM",
            "RULE",
            "RG-FALLBACK",
            "src/B.java"
        )));
        List<LlmChunkReviewOutcome> outcomes = List.of(
            LlmChunkReviewOutcome.llm(
                llmReview,
                new LlmCallResult("{}", 130, 35, 165),
                new LlmVerificationSummary(1, 1, 0, 0)
            ),
            LlmChunkReviewOutcome.fallback(fallbackReview)
        );

        ReviewResult result = aggregator.aggregate(settings(), fullDiff, chunks, outcomes);

        assertThat(result.riskLevel()).isEqualTo("MEDIUM");
        assertThat(result.findings())
            .extracting(ReviewFindingResult::source)
            .containsExactly("LLM", "RULE");
        assertThat(result.llmParseStatus()).isEqualTo(LlmParseStatus.PARTIAL_FALLBACK.code());
        assertThat(result.llmPromptSummary()).contains(
            "chunked=true",
            "chunks=2",
            "aggregateRisk=MEDIUM",
            "aggregateFindings=2",
            "failedChunks=1",
            "verificationAttempted=1",
            "verificationPassed=1"
        );
        assertThat(result.llmPromptTokens()).isEqualTo(130);
        assertThat(result.llmCompletionTokens()).isEqualTo(35);
        assertThat(result.llmTotalTokens()).isEqualTo(165);
        assertThat(result.llmEstimatedCost()).isEqualByComparingTo("0.000270");
    }

    @Test
    void leavesTokenAndCostFieldsEmptyWhenEveryChunkFallsBack() {
        PullRequestDiff fullDiff = diff("src/A.java");
        List<PullRequestDiffChunk> chunks = List.of(chunk(1, 1, "src/A.java"));
        ReviewResult fallbackReview = ReviewResult.completed("LOW", null);

        ReviewResult result = aggregator.aggregate(
            settings(),
            fullDiff,
            chunks,
            List.of(LlmChunkReviewOutcome.fallback(fallbackReview))
        );

        assertThat(result.riskLevel()).isEqualTo("INFO");
        assertThat(result.findings()).isEmpty();
        assertThat(result.llmParseStatus()).isEqualTo(LlmParseStatus.PARTIAL_FALLBACK.code());
        assertThat(result.llmPromptTokens()).isNull();
        assertThat(result.llmCompletionTokens()).isNull();
        assertThat(result.llmTotalTokens()).isNull();
        assertThat(result.llmEstimatedCost()).isNull();
    }

    private PullRequestDiff diff(String... paths) {
        return new PullRequestDiff(
            "octocat",
            "Hello-World",
            1,
            "abc123",
            java.util.Arrays.stream(paths).map(this::file).toList()
        );
    }

    private PullRequestDiffChunk chunk(int index, int total, String path) {
        return new PullRequestDiffChunk(index, total, diff(path), 1, 1, 0, List.of("test"));
    }

    private PullRequestChangedFile file(String path) {
        return new PullRequestChangedFile(path, "modified", 1, 0, "@@ -0,0 +1,1 @@\n+value");
    }

    private ReviewFindingResult finding(String severity, String source, String ruleId, String path) {
        return new ReviewFindingResult(
            severity,
            source,
            ruleId,
            path,
            1,
            "Finding",
            "Apply a targeted fix"
        );
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
