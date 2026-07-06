package com.repoguard.agent.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.config.ReviewPolicySettings;
import com.repoguard.agent.github.GithubPullRequestDiff;
import com.repoguard.agent.observability.RepoGuardMetrics;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
class LlmReviewPipeline {

    private final List<ReviewPipelineStage> stages;
    private final RuleBasedPullRequestReviewer ruleBasedReviewer;
    private final LlmReviewPromptBuilder promptBuilder;
    private final LlmRuleReviewMerger reviewMerger;
    private final LlmReviewQualityScorer qualityScorer;
    private final LlmReviewCostEstimator costEstimator;
    private final LlmChunkReviewAggregator chunkReviewAggregator;
    private final LlmFallbackReasonClassifier fallbackReasonClassifier;
    private final RepoGuardMetrics metrics;
    private final ObjectMapper objectMapper;

    @Autowired
    LlmReviewPipeline(
        RuleBasedPullRequestReviewer ruleBasedReviewer,
        LlmReviewPromptBuilder promptBuilder,
        LlmRuleReviewMerger reviewMerger,
        LlmReviewQualityScorer qualityScorer,
        LlmReviewCostEstimator costEstimator,
        ObjectMapper objectMapper,
        RepoGuardMetrics metrics
    ) {
        this(
            ruleBasedReviewer,
            promptBuilder,
            reviewMerger,
            qualityScorer,
            costEstimator,
            objectMapper,
            metrics,
            new PullRequestDiffChunker()
        );
    }

    LlmReviewPipeline(
        RuleBasedPullRequestReviewer ruleBasedReviewer,
        LlmReviewPromptBuilder promptBuilder,
        LlmRuleReviewMerger reviewMerger,
        ObjectMapper objectMapper,
        RepoGuardMetrics metrics,
        PullRequestDiffChunker diffChunker
    ) {
        this(
            ruleBasedReviewer,
            promptBuilder,
            reviewMerger,
            new LlmReviewQualityScorer(),
            new LlmReviewCostEstimator(),
            objectMapper,
            metrics,
            diffChunker
        );
    }

    LlmReviewPipeline(
        RuleBasedPullRequestReviewer ruleBasedReviewer,
        LlmReviewPromptBuilder promptBuilder,
        LlmRuleReviewMerger reviewMerger,
        LlmReviewQualityScorer qualityScorer,
        LlmReviewCostEstimator costEstimator,
        ObjectMapper objectMapper,
        RepoGuardMetrics metrics,
        PullRequestDiffChunker diffChunker
    ) {
        this.ruleBasedReviewer = ruleBasedReviewer;
        this.promptBuilder = promptBuilder == null ? new LlmReviewPromptBuilder() : promptBuilder;
        this.reviewMerger = reviewMerger == null ? new LlmRuleReviewMerger(new RiskLevelRanker()) : reviewMerger;
        this.qualityScorer = qualityScorer == null ? new LlmReviewQualityScorer() : qualityScorer;
        this.costEstimator = costEstimator == null ? new LlmReviewCostEstimator() : costEstimator;
        this.fallbackReasonClassifier = new LlmFallbackReasonClassifier();
        this.metrics = metrics;
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must be provided");
        this.chunkReviewAggregator = new LlmChunkReviewAggregator(
            this.ruleBasedReviewer,
            this.promptBuilder,
            this.reviewMerger,
            this.qualityScorer,
            this.costEstimator,
            metrics
        );
        this.stages = List.of(
            new LlmReadinessStage(),
            new LlmExecutionStage(diffChunker == null ? new PullRequestDiffChunker() : diffChunker),
            new RuleMergeStage()
        );
    }

    ReviewResult execute(ReviewPipelineContext context) {
        ReviewPipelineState state = ReviewPipelineState.started(context);
        try {
            for (ReviewPipelineStage stage : stages) {
                if (state.completed()) {
                    return state.result();
                }
                state = stage.apply(state);
            }
            return state.result();
        } catch (RuntimeException ex) {
            ReviewPolicySettings settings = context.settings();
            if (settings != null && Boolean.TRUE.equals(settings.fallbackToRules())) {
                return fallbackReview(context, ex.getMessage());
            }
            throw ex;
        }
    }

    private class LlmReadinessStage implements ReviewPipelineStage {
        @Override
        public ReviewPipelineState apply(ReviewPipelineState state) {
            if (isLlmReady(state.context().settings())) {
                return state;
            }
            return state.complete(fallbackReview(state.context(), "LLM config is incomplete"));
        }
    }

    private class LlmExecutionStage implements ReviewPipelineStage {

        private final PullRequestDiffChunker diffChunker;
        private final LlmReviewResultParser reviewResultParser;

        LlmExecutionStage(PullRequestDiffChunker diffChunker) {
            this.diffChunker = diffChunker;
            this.reviewResultParser = new LlmReviewResultParser(objectMapper);
        }

        @Override
        public ReviewPipelineState apply(ReviewPipelineState state) {
            return state.withLlmReview(reviewWithOptionalChunks(state.context()));
        }

        private ReviewResult reviewWithOptionalChunks(ReviewPipelineContext context) {
            ReviewPolicySettings settings = context.settings();
            GithubPullRequestDiff diff = context.diff();
            List<PullRequestDiffChunk> chunks = diffChunker.chunk(diff, settings);
            if (chunks.size() == 1) {
                LlmCallResult callResult = context.llmReviewCaller().callLlm(settings, context.task(), diff);
                ReviewResult parsed = qualityScorer.score(reviewResultParser.parse(callResult.content()), diff);
                return ReviewResult.completed(
                    parsed.riskLevel(),
                    parsed.findings(),
                    null,
                    null,
                    null,
                    null,
                    promptBuilder.promptSummary(diff),
                    callResult.promptTokens(),
                    callResult.completionTokens(),
                    callResult.totalTokens(),
                    costEstimator.estimate(settings, callResult.promptTokens(), callResult.completionTokens())
                );
            }

            return chunkReviewAggregator.aggregate(context, diff, chunks, reviewResultParser);
        }
    }

    private class RuleMergeStage implements ReviewPipelineStage {
        @Override
        public ReviewPipelineState apply(ReviewPipelineState state) {
            ReviewPipelineContext context = state.context();
            ReviewResult ruleReview = ruleBasedReviewer.review(context.diff());
            ReviewResult parsed = state.llmReview();
            ReviewResult merged = reviewMerger.mergeWithRuleReview(parsed, ruleReview);
            ReviewPolicySettings settings = context.settings();
            ReviewResult completed = ReviewResult.completed(
                merged.riskLevel(),
                merged.findings(),
                settings.llmProvider(),
                settings.modelName(),
                elapsedMillis(context.startedAtNanos()),
                parsed.llmParseStatus() == null ? "parsed" : parsed.llmParseStatus(),
                reviewMerger.hybridPromptSummary(
                    parsed.llmPromptSummary() == null ? context.promptSummary() : parsed.llmPromptSummary(),
                    ruleReview,
                    merged
                ),
                parsed.llmPromptTokens(),
                parsed.llmCompletionTokens(),
                parsed.llmTotalTokens(),
                parsed.llmEstimatedCost()
            );
            return state.withRuleReview(ruleReview).complete(completed);
        }
    }

    private ReviewResult fallbackReview(ReviewPipelineContext context, String reason) {
        if (metrics != null) {
            metrics.llmFallback(fallbackReasonClassifier.category(reason));
        }
        ReviewResult fallback = ruleBasedReviewer.review(context.diff());
        ReviewPolicySettings settings = context.settings();
        return ReviewResult.fallback(
            fallback.riskLevel(),
            fallbackReasonClassifier.normalizeReason(reason),
            fallback.findings(),
            settings == null ? null : settings.llmProvider(),
            settings == null ? null : settings.modelName(),
            elapsedMillis(context.startedAtNanos()),
            context.promptSummary()
        );
    }

    private boolean isLlmReady(ReviewPolicySettings settings) {
        return settings != null && settings.exists() && settings.enabled() && settings.readyForLlmReview();
    }

    private Integer elapsedMillis(long startedAt) {
        long elapsed = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
        return elapsed > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) elapsed;
    }

    private Integer zeroToNull(int value) {
        return value <= 0 ? null : value;
    }

}
