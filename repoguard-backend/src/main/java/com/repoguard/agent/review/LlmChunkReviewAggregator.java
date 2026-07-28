package com.repoguard.agent.review;

import com.repoguard.agent.review.ReviewPolicySettings;
import com.repoguard.agent.github.GithubPullRequestDiff;
import com.repoguard.agent.observability.LogContext;
import com.repoguard.agent.observability.RepoGuardMetrics;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class LlmChunkReviewAggregator {

    static final String CHUNK_PARTIAL_FAILURE_CATEGORY = "chunk_partial_failure";
    static final String BUDGET_EXHAUSTED_CATEGORY = "budget_exhausted";
    static final String CHUNK_LIMIT_EXCEEDED_CATEGORY = "chunk_limit_exceeded";
    static final String EXECUTOR_REJECTED_CATEGORY = "executor_rejected";

    private static final Logger LOGGER = LoggerFactory.getLogger(LlmChunkReviewAggregator.class);

    private final RuleBasedPullRequestReviewer ruleBasedReviewer;
    private final LlmReviewPromptBuilder promptBuilder;
    private final LlmRuleReviewMerger reviewMerger;
    private final LlmReviewQualityScorer qualityScorer;
    private final LlmReviewCostEstimator costEstimator;
    private final RepoGuardMetrics metrics;
    private final Executor chunkExecutor;
    private final int maxTotalChunks;
    private final int maxInFlightChunks;

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
        this.chunkExecutor = Objects.requireNonNull(chunkExecutor, "chunkExecutor");
        this.maxTotalChunks = requirePositive(maxTotalChunks, "maxTotalChunks");
        this.maxInFlightChunks = requirePositive(maxInFlightChunks, "maxInFlightChunks");
    }

    ReviewResult aggregate(
        ReviewPipelineContext context,
        GithubPullRequestDiff fullDiff,
        List<PullRequestDiffChunk> chunks,
        LlmReviewResultParser reviewResultParser,
        ReviewBudget budget
    ) {
        ReviewPolicySettings settings = context.settings();
        String traceId = LogContext.currentTraceId();
        List<ChunkReviewOutcome> outcomes = new ArrayList<>(Collections.nCopies(chunks.size(), null));
        Deque<PendingChunk> inFlight = new ArrayDeque<>(maxInFlightChunks);
        int llmChunkLimit = Math.min(chunks.size(), maxTotalChunks);
        int nextChunkIndex = 0;

        try {
            nextChunkIndex = fillWindow(
                context,
                settings,
                chunks,
                reviewResultParser,
                traceId,
                budget,
                outcomes,
                inFlight,
                nextChunkIndex,
                llmChunkLimit
            );
            while (!inFlight.isEmpty()) {
                PendingChunk pending = inFlight.removeFirst();
                AwaitedChunk awaited = await(pending, budget);
                outcomes.set(pending.index(), awaited.outcome());
                if (awaited.budgetExhausted()) {
                    harvestCompletedAndCancelRemaining(inFlight, outcomes);
                    break;
                }
                nextChunkIndex = fillWindow(
                    context,
                    settings,
                    chunks,
                    reviewResultParser,
                    traceId,
                    budget,
                    outcomes,
                    inFlight,
                    nextChunkIndex,
                    llmChunkLimit
                );
            }
        } catch (RuntimeException ex) {
            cancelRemaining(inFlight);
            throw ex;
        }

        fillFallbacks(
            chunks,
            outcomes,
            nextChunkIndex,
            llmChunkLimit,
            BUDGET_EXHAUSTED_CATEGORY
        );
        fillFallbacks(
            chunks,
            outcomes,
            llmChunkLimit,
            chunks.size(),
            CHUNK_LIMIT_EXCEEDED_CATEGORY
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

    private int fillWindow(
        ReviewPipelineContext context,
        ReviewPolicySettings settings,
        List<PullRequestDiffChunk> chunks,
        LlmReviewResultParser reviewResultParser,
        String traceId,
        ReviewBudget budget,
        List<ChunkReviewOutcome> outcomes,
        Deque<PendingChunk> inFlight,
        int nextChunkIndex,
        int llmChunkLimit
    ) {
        int next = nextChunkIndex;
        while (next < llmChunkLimit && inFlight.size() < maxInFlightChunks && !budget.exhausted()) {
            PullRequestDiffChunk chunk = chunks.get(next);
            int outcomeIndex = next;
            FutureTask<ChunkReviewOutcome> future = new FutureTask<>(
                () -> reviewChunk(context, settings, chunk, reviewResultParser, traceId, budget)
            );
            try {
                chunkExecutor.execute(future);
                inFlight.addLast(new PendingChunk(outcomeIndex, chunk, future));
            } catch (RejectedExecutionException ex) {
                outcomes.set(
                    outcomeIndex,
                    degradeToRules(chunk, EXECUTOR_REJECTED_CATEGORY, ex)
                );
            }
            next++;
        }
        return next;
    }

    private AwaitedChunk await(PendingChunk pending, ReviewBudget budget) {
        FutureTask<ChunkReviewOutcome> future = pending.future();
        try {
            long remainingNanos = budget.remainingNanos();
            if (remainingNanos <= 0 && !future.isDone()) {
                future.cancel(true);
                return new AwaitedChunk(
                    degradeToRules(pending.chunk(), BUDGET_EXHAUSTED_CATEGORY, null),
                    true
                );
            }
            ChunkReviewOutcome outcome = remainingNanos <= 0
                ? future.get()
                : future.get(remainingNanos, TimeUnit.NANOSECONDS);
            return new AwaitedChunk(outcome, budget.exhausted());
        } catch (TimeoutException ex) {
            future.cancel(true);
            return new AwaitedChunk(
                degradeToRules(pending.chunk(), BUDGET_EXHAUSTED_CATEGORY, null),
                true
            );
        } catch (CancellationException ex) {
            return new AwaitedChunk(
                degradeToRules(pending.chunk(), BUDGET_EXHAUSTED_CATEGORY, null),
                budget.exhausted()
            );
        } catch (ExecutionException ex) {
            throw ex.getCause() instanceof RuntimeException cause ? cause : new CompletionException(ex.getCause());
        } catch (InterruptedException ex) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new CompletionException(ex);
        }
    }

    private void harvestCompletedAndCancelRemaining(
        Deque<PendingChunk> inFlight,
        List<ChunkReviewOutcome> outcomes
    ) {
        while (!inFlight.isEmpty()) {
            PendingChunk pending = inFlight.removeFirst();
            FutureTask<ChunkReviewOutcome> future = pending.future();
            if (!future.isDone()) {
                boolean cancelled = future.cancel(true);
                if (cancelled) {
                    outcomes.set(
                        pending.index(),
                        degradeToRules(pending.chunk(), BUDGET_EXHAUSTED_CATEGORY, null)
                    );
                    continue;
                }
            }
            outcomes.set(pending.index(), completedOutcome(pending));
        }
    }

    private ChunkReviewOutcome completedOutcome(PendingChunk pending) {
        try {
            return pending.future().get();
        } catch (CancellationException ex) {
            return degradeToRules(pending.chunk(), BUDGET_EXHAUSTED_CATEGORY, null);
        } catch (ExecutionException ex) {
            throw ex.getCause() instanceof RuntimeException cause ? cause : new CompletionException(ex.getCause());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new CompletionException(ex);
        }
    }

    private void fillFallbacks(
        List<PullRequestDiffChunk> chunks,
        List<ChunkReviewOutcome> outcomes,
        int fromInclusive,
        int toExclusive,
        String category
    ) {
        for (int index = fromInclusive; index < toExclusive; index++) {
            if (outcomes.get(index) == null) {
                outcomes.set(index, degradeToRules(chunks.get(index), category, null));
            }
        }
    }

    private void cancelRemaining(Deque<PendingChunk> inFlight) {
        for (PendingChunk pending : inFlight) {
            pending.future().cancel(true);
        }
        inFlight.clear();
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

    private int requirePositive(int value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private record PendingChunk(
        int index,
        PullRequestDiffChunk chunk,
        FutureTask<ChunkReviewOutcome> future
    ) {
    }

    private record AwaitedChunk(ChunkReviewOutcome outcome, boolean budgetExhausted) {
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
