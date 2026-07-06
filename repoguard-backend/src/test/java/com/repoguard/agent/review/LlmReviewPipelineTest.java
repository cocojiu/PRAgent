package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

class LlmReviewPipelineTest {

    private final RuleBasedPullRequestReviewer ruleBasedReviewer = org.mockito.Mockito.mock(RuleBasedPullRequestReviewer.class);
    private final LlmReviewPromptBuilder promptBuilder = new LlmReviewPromptBuilder();
    private final LlmRuleReviewMerger reviewMerger = new LlmRuleReviewMerger(new RiskLevelRanker());
    private final LlmReviewQualityScorer qualityScorer = new LlmReviewQualityScorer();
    private final LlmReviewCostEstimator costEstimator = new LlmReviewCostEstimator();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LlmFallbackReasonClassifier fallbackReasonClassifier = new LlmFallbackReasonClassifier();
    private final PullRequestDiffChunker diffChunker = DiffChunkingTestFixtures.chunker();

    @Test
    void constructorRejectsMissingExplicitDependencies() {
        assertMissing("ruleBasedReviewer", () -> pipeline(null, promptBuilder, reviewMerger, qualityScorer, costEstimator, objectMapper, fallbackReasonClassifier, diffChunker));
        assertMissing("promptBuilder", () -> pipeline(ruleBasedReviewer, null, reviewMerger, qualityScorer, costEstimator, objectMapper, fallbackReasonClassifier, diffChunker));
        assertMissing("reviewMerger", () -> pipeline(ruleBasedReviewer, promptBuilder, null, qualityScorer, costEstimator, objectMapper, fallbackReasonClassifier, diffChunker));
        assertMissing("qualityScorer", () -> pipeline(ruleBasedReviewer, promptBuilder, reviewMerger, null, costEstimator, objectMapper, fallbackReasonClassifier, diffChunker));
        assertMissing("costEstimator", () -> pipeline(ruleBasedReviewer, promptBuilder, reviewMerger, qualityScorer, null, objectMapper, fallbackReasonClassifier, diffChunker));
        assertMissing("objectMapper", () -> pipeline(ruleBasedReviewer, promptBuilder, reviewMerger, qualityScorer, costEstimator, null, fallbackReasonClassifier, diffChunker));
        assertMissing("fallbackReasonClassifier", () -> pipeline(ruleBasedReviewer, promptBuilder, reviewMerger, qualityScorer, costEstimator, objectMapper, null, diffChunker));
        assertMissing("diffChunker", () -> pipeline(ruleBasedReviewer, promptBuilder, reviewMerger, qualityScorer, costEstimator, objectMapper, fallbackReasonClassifier, null));
    }

    private void assertMissing(String dependencyName, ThrowingCallable callable) {
        assertThatThrownBy(callable)
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining(dependencyName);
    }

    private LlmReviewPipeline pipeline(
        RuleBasedPullRequestReviewer ruleBasedReviewer,
        LlmReviewPromptBuilder promptBuilder,
        LlmRuleReviewMerger reviewMerger,
        LlmReviewQualityScorer qualityScorer,
        LlmReviewCostEstimator costEstimator,
        ObjectMapper objectMapper,
        LlmFallbackReasonClassifier fallbackReasonClassifier,
        PullRequestDiffChunker diffChunker
    ) {
        return new LlmReviewPipeline(
            ruleBasedReviewer,
            promptBuilder,
            reviewMerger,
            qualityScorer,
            costEstimator,
            objectMapper,
            null,
            fallbackReasonClassifier,
            diffChunker
        );
    }
}
