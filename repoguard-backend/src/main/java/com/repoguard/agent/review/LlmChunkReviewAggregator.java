package com.repoguard.agent.review;

import com.repoguard.agent.config.ReviewPolicySettings;
import com.repoguard.agent.github.GithubPullRequestDiff;
import com.repoguard.agent.observability.RepoGuardMetrics;
import java.util.ArrayList;
import java.util.List;

class LlmChunkReviewAggregator {

    static final String PARTIAL_FALLBACK_STATUS = "partial_fallback";
    static final String CHUNK_PARTIAL_FAILURE_CATEGORY = "chunk_partial_failure";

    private final RuleBasedPullRequestReviewer ruleBasedReviewer;
    private final LlmReviewPromptBuilder promptBuilder;
    private final LlmRuleReviewMerger reviewMerger;
    private final LlmReviewQualityScorer qualityScorer;
    private final LlmReviewCostEstimator costEstimator;
    private final RepoGuardMetrics metrics;

    LlmChunkReviewAggregator(
        RuleBasedPullRequestReviewer ruleBasedReviewer,
        LlmReviewPromptBuilder promptBuilder,
        LlmRuleReviewMerger reviewMerger,
        LlmReviewQualityScorer qualityScorer,
        LlmReviewCostEstimator costEstimator,
        RepoGuardMetrics metrics
    ) {
        this.ruleBasedReviewer = ruleBasedReviewer;
        this.promptBuilder = promptBuilder;
        this.reviewMerger = reviewMerger;
        this.qualityScorer = qualityScorer;
        this.costEstimator = costEstimator;
        this.metrics = metrics;
    }

    ReviewResult aggregate(
        ReviewPipelineContext context,
        GithubPullRequestDiff fullDiff,
        List<PullRequestDiffChunk> chunks,
        LlmReviewResultParser reviewResultParser
    ) {
        ReviewPolicySettings settings = context.settings();
        ChunkAggregation aggregation = ChunkAggregation.empty();
        for (PullRequestDiffChunk chunk : chunks) {
            aggregation = reviewChunk(context, settings, chunk, reviewResultParser, aggregation);
        }
        return ReviewResult.completed(
            aggregation.riskLevel(),
            aggregation.findings(),
            null,
            null,
            null,
            aggregation.failedChunks() > 0 ? PARTIAL_FALLBACK_STATUS : null,
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

    private ChunkAggregation reviewChunk(
        ReviewPipelineContext context,
        ReviewPolicySettings settings,
        PullRequestDiffChunk chunk,
        LlmReviewResultParser reviewResultParser,
        ChunkAggregation aggregation
    ) {
        try {
            LlmCallResult callResult = context.llmReviewCaller().callLlm(settings, context.task(), chunk.diff());
            ReviewResult parsed = qualityScorer.score(reviewResultParser.parse(callResult.content()), chunk.diff());
            return aggregation.addLlmResult(parsed, callResult, reviewMerger);
        } catch (RuntimeException ex) {
            if (metrics != null) {
                metrics.llmFallback(CHUNK_PARTIAL_FAILURE_CATEGORY);
            }
            ReviewResult ruleReview = ruleBasedReviewer.review(chunk.diff());
            return aggregation.addFallbackResult(ruleReview, reviewMerger);
        }
    }

    private Integer zeroToNull(int value) {
        return value <= 0 ? null : value;
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
