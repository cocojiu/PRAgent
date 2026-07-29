package com.repoguard.agent.review;

import com.repoguard.agent.review.ReviewPolicySettings;
import com.repoguard.agent.observability.LogContext;
import com.repoguard.agent.observability.RepoGuardMetrics;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class LlmChunkReviewAggregator {

    static final String CHUNK_PARTIAL_FAILURE_CATEGORY = "chunk_partial_failure";
    static final String BUDGET_EXHAUSTED_CATEGORY = LlmChunkReviewScheduler.BUDGET_EXHAUSTED_CATEGORY;
    static final String CHUNK_LIMIT_EXCEEDED_CATEGORY = LlmChunkReviewScheduler.CHUNK_LIMIT_EXCEEDED_CATEGORY;
    static final String EXECUTOR_REJECTED_CATEGORY = LlmChunkReviewScheduler.EXECUTOR_REJECTED_CATEGORY;

    private static final Logger LOGGER = LoggerFactory.getLogger(LlmChunkReviewAggregator.class);

    private final RuleBasedPullRequestReviewer ruleBasedReviewer;
    private final LlmReviewPromptBuilder promptBuilder;
    private final LlmRuleReviewMerger reviewMerger;
    private final LlmReviewQualityScorer qualityScorer;
    private final LlmReviewCostEstimator costEstimator;
    private final RepoGuardMetrics metrics;
    private final LlmChunkReviewScheduler chunkReviewScheduler;

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
        this.ruleBasedReviewer = Objects.requireNonNull(ruleBasedReviewer, "ruleBasedReviewer");
        this.promptBuilder = Objects.requireNonNull(promptBuilder, "promptBuilder");
        this.reviewMerger = Objects.requireNonNull(reviewMerger, "reviewMerger");
        this.qualityScorer = Objects.requireNonNull(qualityScorer, "qualityScorer");
        this.costEstimator = Objects.requireNonNull(costEstimator, "costEstimator");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.chunkReviewScheduler = new LlmChunkReviewScheduler(
            chunkExecutor,
            maxTotalChunks,
            maxInFlightChunks
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
        List<ChunkReviewOutcome> outcomes = chunkReviewScheduler.schedule(
            chunks,
            budget,
            chunk -> reviewChunk(context, settings, chunk, reviewResultParser, traceId, budget),
            this::degradeToRules
        );

        ChunkAggregation aggregation = ChunkAggregation.empty();
        for (ChunkReviewOutcome outcome : outcomes) {
            aggregation = Objects.requireNonNull(outcome, "chunk outcome").applyTo(aggregation, reviewMerger);
        }
        return ReviewResult.completed(
            aggregation.riskLevel(),
            aggregation.findings(),
            null,
            null,
            null,
            aggregation.failedChunks() > 0 ? LlmParseStatus.PARTIAL_FALLBACK.code() : null,
            promptBuilder.chunkedPromptSummary(
                fullDiff,
                chunks,
                aggregation.findings().size(),
                aggregation.riskLevel(),
                aggregation.failedChunks()
            ),
            zeroToNull(aggregation.promptTokens()),
            zeroToNull(aggregation.completionTokens()),
            zeroToNull(aggregation.totalTokens()),
            costEstimator.estimate(
                settings,
                zeroToNull(aggregation.promptTokens()),
                zeroToNull(aggregation.completionTokens())
            )
        );
    }

    private ChunkReviewOutcome reviewChunk(
        ReviewPipelineContext context,
        ReviewPolicySettings settings,
        PullRequestDiffChunk chunk,
        LlmReviewResultParser reviewResultParser,
        String traceId,
        ReviewBudget budget
    ) {
        try (LogContext.Scope ignored = LogContext.withReviewTask(context.task(), traceId)) {
            // Chunks are all queued up front, so a chunk may only reach a worker
            // thread long after the budget ran out. Degrade before spending on a
            // call that the pipeline can no longer wait for.
            if (budget.exhausted()) {
                return degradeToRules(chunk, BUDGET_EXHAUSTED_CATEGORY, null);
            }
            try {
                LlmCallResult callResult = context.llmReviewCaller().callLlm(settings, context.task(), chunk.diff());
                ReviewResult parsed = qualityScorer.score(reviewResultParser.parse(callResult.content()), chunk.diff());
                return ChunkReviewOutcome.llm(parsed, callResult);
            } catch (RuntimeException ex) {
                return degradeToRules(chunk, CHUNK_PARTIAL_FAILURE_CATEGORY, ex);
            }
        }
    }

    /**
     * Falls back to the rule-based reviewer for one chunk. The aggregation counts
     * it as a failed chunk, which surfaces as {@code PARTIAL_FALLBACK}.
     */
    private ChunkReviewOutcome degradeToRules(
        PullRequestDiffChunk chunk,
        String category,
        RuntimeException failure
    ) {
        if (failure == null) {
            LOGGER.warn(
                "LLM chunk review skipped chunkIndex={} chunkTotal={} operation=llm_chunk_review "
                    + "result=fallback reason={}",
                chunk.index(),
                chunk.total(),
                category
            );
        } else {
            LOGGER.warn(
                "LLM chunk review failed chunkIndex={} chunkTotal={} operation=llm_chunk_review "
                    + "result=fallback reason={} exceptionType={}",
                chunk.index(),
                chunk.total(),
                category,
                failure.getClass().getName(),
                failure
            );
        }
        metrics.llmFallback(category);
        return ChunkReviewOutcome.fallback(ruleBasedReviewer.review(chunk.diff()));
    }

    private Integer zeroToNull(int value) {
        return value <= 0 ? null : value;
    }

    private record ChunkReviewOutcome(ReviewResult review, LlmCallResult callResult) {

        static ChunkReviewOutcome llm(ReviewResult parsed, LlmCallResult callResult) {
            return new ChunkReviewOutcome(parsed, callResult);
        }

        static ChunkReviewOutcome fallback(ReviewResult ruleReview) {
            return new ChunkReviewOutcome(ruleReview, null);
        }

        ChunkAggregation applyTo(ChunkAggregation aggregation, LlmRuleReviewMerger reviewMerger) {
            return callResult == null
                ? aggregation.addFallbackResult(review, reviewMerger)
                : aggregation.addLlmResult(review, callResult, reviewMerger);
        }
    }

    private record ChunkAggregation(
        String riskLevel,
        List<ReviewFindingResult> findings,
        int promptTokens,
        int completionTokens,
        int totalTokens,
        int failedChunks
    ) {
        static ChunkAggregation empty() {
            return new ChunkAggregation("INFO", new ArrayList<>(), 0, 0, 0, 0);
        }

        ChunkAggregation addLlmResult(
            ReviewResult parsed,
            LlmCallResult callResult,
            LlmRuleReviewMerger reviewMerger
        ) {
            List<ReviewFindingResult> nextFindings = new ArrayList<>(findings);
            if (parsed.findings() != null) {
                nextFindings.addAll(parsed.findings());
            }
            return new ChunkAggregation(
                reviewMerger.maxRisk(riskLevel, parsed.riskLevel()),
                nextFindings,
                promptTokens + safeInt(callResult.promptTokens()),
                completionTokens + safeInt(callResult.completionTokens()),
                totalTokens + safeInt(callResult.totalTokens()),
                failedChunks
            );
        }

        ChunkAggregation addFallbackResult(ReviewResult ruleReview, LlmRuleReviewMerger reviewMerger) {
            List<ReviewFindingResult> nextFindings = new ArrayList<>(findings);
            if (ruleReview.findings() != null) {
                nextFindings.addAll(ruleReview.findings());
            }
            return new ChunkAggregation(
                reviewMerger.maxRisk(riskLevel, ruleReview.riskLevel()),
                nextFindings,
                promptTokens,
                completionTokens,
                totalTokens,
                failedChunks + 1
            );
        }

        private static int safeInt(Integer value) {
            return value == null ? 0 : value;
        }
    }
}
