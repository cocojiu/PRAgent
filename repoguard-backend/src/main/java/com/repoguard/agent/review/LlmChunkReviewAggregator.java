package com.repoguard.agent.review;

import com.repoguard.agent.review.ReviewPolicySettings;
import com.repoguard.agent.observability.LogContext;
import com.repoguard.agent.observability.RepoGuardMetrics;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

class LlmChunkReviewAggregator {

    static final String CHUNK_PARTIAL_FAILURE_CATEGORY =
        LlmChunkReviewFallbackHandler.CHUNK_PARTIAL_FAILURE_CATEGORY;
    static final String BUDGET_EXHAUSTED_CATEGORY = LlmChunkReviewScheduler.BUDGET_EXHAUSTED_CATEGORY;
    static final String CHUNK_LIMIT_EXCEEDED_CATEGORY = LlmChunkReviewScheduler.CHUNK_LIMIT_EXCEEDED_CATEGORY;
    static final String EXECUTOR_REJECTED_CATEGORY = LlmChunkReviewScheduler.EXECUTOR_REJECTED_CATEGORY;

    private final LlmReviewQualityScorer qualityScorer;
    private final LlmChunkReviewScheduler chunkReviewScheduler;
    private final LlmChunkReviewFallbackHandler fallbackHandler;
    private final LlmChunkReviewResultAggregator resultAggregator;

    LlmChunkReviewAggregator(
        RuleBasedPullRequestReviewer ruleBasedReviewer,
        LlmReviewPromptBuilder promptBuilder,
        LlmRuleReviewMerger reviewMerger,
        LlmReviewQualityScorer qualityScorer,
        LlmReviewCostEstimator costEstimator,
        RepoGuardMetrics metrics,
        Executor chunkExecutor,
        int maxTotalChunks,
        int maxInFlightChunks
    ) {
        RuleBasedPullRequestReviewer requiredRuleBasedReviewer = Objects.requireNonNull(
            ruleBasedReviewer,
            "ruleBasedReviewer"
        );
        LlmReviewPromptBuilder requiredPromptBuilder = Objects.requireNonNull(promptBuilder, "promptBuilder");
        LlmRuleReviewMerger requiredReviewMerger = Objects.requireNonNull(reviewMerger, "reviewMerger");
        this.qualityScorer = Objects.requireNonNull(qualityScorer, "qualityScorer");
        LlmReviewCostEstimator requiredCostEstimator = Objects.requireNonNull(costEstimator, "costEstimator");
        RepoGuardMetrics requiredMetrics = Objects.requireNonNull(metrics, "metrics");
        this.chunkReviewScheduler = new LlmChunkReviewScheduler(
            chunkExecutor,
            maxTotalChunks,
            maxInFlightChunks
        );
        this.fallbackHandler = new LlmChunkReviewFallbackHandler(
            requiredRuleBasedReviewer,
            requiredMetrics
        );
        this.resultAggregator = new LlmChunkReviewResultAggregator(
            requiredPromptBuilder,
            requiredReviewMerger,
            requiredCostEstimator
        );
    }

    ReviewResult aggregate(
        ReviewPipelineContext context,
        PullRequestDiff fullDiff,
        List<PullRequestDiffChunk> chunks,
        LlmReviewResultParser reviewResultParser,
        ReviewBudget budget
    ) {
        ReviewPolicySettings settings = context.settings();
        String traceId = LogContext.currentTraceId();
        List<LlmChunkReviewOutcome> outcomes = chunkReviewScheduler.schedule(
            chunks,
            budget,
            chunk -> reviewChunk(context, settings, chunk, reviewResultParser, traceId, budget),
            fallbackHandler::fallback
        );
        return resultAggregator.aggregate(settings, fullDiff, chunks, outcomes);
    }

    private LlmChunkReviewOutcome reviewChunk(
        ReviewPipelineContext context,
        ReviewPolicySettings settings,
        PullRequestDiffChunk chunk,
        LlmReviewResultParser reviewResultParser,
        String traceId,
        ReviewBudget budget
    ) {
        try (LogContext.Scope _ = LogContext.withReviewTask(context.task(), traceId)) {
            // Chunks are all queued up front, so a chunk may only reach a worker
            // thread long after the budget ran out. Degrade before spending on a
            // call that the pipeline can no longer wait for.
            if (budget.exhausted()) {
                return fallbackHandler.fallback(chunk, BUDGET_EXHAUSTED_CATEGORY, null);
            }
            try {
                LlmCallResult callResult = context.llmReviewCaller().callLlm(settings, context.task(), chunk.diff());
                ReviewResult parsed = qualityScorer.score(reviewResultParser.parse(callResult.content()), chunk.diff());
                return LlmChunkReviewOutcome.llm(parsed, callResult);
            } catch (RuntimeException ex) {
                return fallbackHandler.fallback(chunk, CHUNK_PARTIAL_FAILURE_CATEGORY, ex);
            }
        }
    }
}
