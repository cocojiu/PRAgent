package com.repoguard.agent.review;

import com.repoguard.agent.config.ReviewPolicySettings;
import com.repoguard.agent.github.GithubPullRequestDiff;
import com.repoguard.agent.observability.LogContext;
import com.repoguard.agent.observability.RepoGuardMetrics;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class LlmChunkReviewAggregator {

    static final String CHUNK_PARTIAL_FAILURE_CATEGORY = "chunk_partial_failure";

    private static final Logger LOGGER = LoggerFactory.getLogger(LlmChunkReviewAggregator.class);

    private final RuleBasedPullRequestReviewer ruleBasedReviewer;
    private final LlmReviewPromptBuilder promptBuilder;
    private final LlmRuleReviewMerger reviewMerger;
    private final LlmReviewQualityScorer qualityScorer;
    private final LlmReviewCostEstimator costEstimator;
    private final RepoGuardMetrics metrics;
    private final Executor chunkExecutor;

    LlmChunkReviewAggregator(
        RuleBasedPullRequestReviewer ruleBasedReviewer,
        LlmReviewPromptBuilder promptBuilder,
        LlmRuleReviewMerger reviewMerger,
        LlmReviewQualityScorer qualityScorer,
        LlmReviewCostEstimator costEstimator,
        RepoGuardMetrics metrics,
        Executor chunkExecutor
    ) {
        this.ruleBasedReviewer = Objects.requireNonNull(ruleBasedReviewer, "ruleBasedReviewer");
        this.promptBuilder = Objects.requireNonNull(promptBuilder, "promptBuilder");
        this.reviewMerger = Objects.requireNonNull(reviewMerger, "reviewMerger");
        this.qualityScorer = Objects.requireNonNull(qualityScorer, "qualityScorer");
        this.costEstimator = Objects.requireNonNull(costEstimator, "costEstimator");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.chunkExecutor = Objects.requireNonNull(chunkExecutor, "chunkExecutor");
    }

    ReviewResult aggregate(
        ReviewPipelineContext context,
        GithubPullRequestDiff fullDiff,
        List<PullRequestDiffChunk> chunks,
        LlmReviewResultParser reviewResultParser
    ) {
        ReviewPolicySettings settings = context.settings();
        String traceId = LogContext.currentTraceId();
        List<CompletableFuture<ChunkReviewOutcome>> outcomes = new ArrayList<>(chunks.size());
        for (PullRequestDiffChunk chunk : chunks) {
            outcomes.add(CompletableFuture.supplyAsync(
                () -> reviewChunk(context, settings, chunk, reviewResultParser, traceId),
                chunkExecutor
            ));
        }
        ChunkAggregation aggregation = ChunkAggregation.empty();
        for (CompletableFuture<ChunkReviewOutcome> outcome : outcomes) {
            aggregation = join(outcome).applyTo(aggregation, reviewMerger);
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
        String traceId
    ) {
        try (LogContext.Scope ignored = LogContext.withReviewTask(context.task(), traceId)) {
            try {
                LlmCallResult callResult = context.llmReviewCaller().callLlm(settings, context.task(), chunk.diff());
                ReviewResult parsed = qualityScorer.score(reviewResultParser.parse(callResult.content()), chunk.diff());
                return ChunkReviewOutcome.llm(parsed, callResult);
            } catch (RuntimeException ex) {
                LOGGER.warn(
                    "LLM chunk review failed chunkIndex={} chunkTotal={} operation=llm_chunk_review result=fallback exceptionType={}",
                    chunk.index(),
                    chunk.total(),
                    ex.getClass().getName(),
                    ex
                );
                metrics.llmFallback(CHUNK_PARTIAL_FAILURE_CATEGORY);
                ReviewResult ruleReview = ruleBasedReviewer.review(chunk.diff());
                return ChunkReviewOutcome.fallback(ruleReview);
            }
        }
    }

    private ChunkReviewOutcome join(CompletableFuture<ChunkReviewOutcome> outcome) {
        try {
            return outcome.join();
        } catch (CompletionException ex) {
            throw ex.getCause() instanceof RuntimeException cause ? cause : ex;
        }
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
