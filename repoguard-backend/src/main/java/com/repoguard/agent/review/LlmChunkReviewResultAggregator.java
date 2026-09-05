package com.repoguard.agent.review;

import com.repoguard.agent.review.ReviewPolicySettings;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Merges ordered LLM and fallback chunk outcomes into one review result. */
final class LlmChunkReviewResultAggregator {

    private final LlmReviewPromptBuilder promptBuilder;
    private final LlmRuleReviewMerger reviewMerger;
    private final LlmReviewCostEstimator costEstimator;

    LlmChunkReviewResultAggregator(
        LlmReviewPromptBuilder promptBuilder,
        LlmRuleReviewMerger reviewMerger,
        LlmReviewCostEstimator costEstimator
    ) {
        this.promptBuilder = Objects.requireNonNull(promptBuilder, "promptBuilder");
        this.reviewMerger = Objects.requireNonNull(reviewMerger, "reviewMerger");
        this.costEstimator = Objects.requireNonNull(costEstimator, "costEstimator");
    }

    ReviewResult aggregate(
        ReviewPolicySettings settings,
        PullRequestDiff fullDiff,
        List<PullRequestDiffChunk> chunks,
        List<LlmChunkReviewOutcome> outcomes
    ) {
        return aggregate(settings, fullDiff, chunks, outcomes, LlmReviewContext.legacy());
    }

    ReviewResult aggregate(
        ReviewPolicySettings settings,
        PullRequestDiff fullDiff,
        List<PullRequestDiffChunk> chunks,
        List<LlmChunkReviewOutcome> outcomes,
        LlmReviewContext promptContext
    ) {
        ChunkAggregation aggregation = ChunkAggregation.empty();
        for (LlmChunkReviewOutcome outcome : outcomes) {
            aggregation = addOutcome(
                aggregation,
                Objects.requireNonNull(outcome, "chunk outcome")
            );
        }
        ReviewResult finalized = reviewMerger.mergeWithRuleReview(
            ReviewResult.completed("INFO", aggregation.findings()),
            null
        );
        return ReviewResult.completed(
            finalized.riskLevel(),
            finalized.findings(),
            null,
            null,
            null,
            aggregation.failedChunks() > 0 ? LlmParseStatus.PARTIAL_FALLBACK.code() : null,
            promptBuilder.chunkedPromptSummary(
                fullDiff,
                chunks,
                finalized.findings().size(),
                finalized.riskLevel(),
                aggregation.failedChunks(),
                promptContext,
                aggregation.verificationSummary()
            ),
            zeroToNull(aggregation.promptTokens()),
            zeroToNull(aggregation.completionTokens()),
            zeroToNull(aggregation.totalTokens()),
            costEstimator.estimate(
                settings,
                zeroToNull(aggregation.promptTokens()),
                zeroToNull(aggregation.completionTokens())
            )
        ).withStatusDetail(aggregation.failureDetail());
    }

    private ChunkAggregation addOutcome(
        ChunkAggregation aggregation,
        LlmChunkReviewOutcome outcome
    ) {
        return outcome.callResult() == null
            ? aggregation.addFallbackResult(outcome.review(), outcome.failureCategory(), reviewMerger)
            : aggregation.addLlmResult(
                outcome.review(),
                outcome.callResult(),
                outcome.verificationSummary(),
                reviewMerger
            );
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
        int failedChunks,
        LlmVerificationSummary verificationSummary,
        List<String> failureCategories
    ) {
        static ChunkAggregation empty() {
            return new ChunkAggregation(
                "INFO",
                new ArrayList<>(),
                0,
                0,
                0,
                0,
                LlmVerificationSummary.empty(),
                List.of()
            );
        }

        ChunkAggregation addLlmResult(
            ReviewResult parsed,
            LlmCallResult callResult,
            LlmVerificationSummary verification,
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
                failedChunks,
                verificationSummary.add(verification),
                failureCategories
            );
        }

        ChunkAggregation addFallbackResult(
            ReviewResult ruleReview,
            String failureCategory,
            LlmRuleReviewMerger reviewMerger
        ) {
            List<ReviewFindingResult> nextFindings = new ArrayList<>(findings);
            if (ruleReview.findings() != null) {
                nextFindings.addAll(ruleReview.findings());
            }
            List<String> nextFailureCategories = new ArrayList<>(failureCategories);
            nextFailureCategories.add(failureCategory);
            return new ChunkAggregation(
                reviewMerger.maxRisk(riskLevel, ruleReview.riskLevel()),
                nextFindings,
                promptTokens,
                completionTokens,
                totalTokens,
                failedChunks + 1,
                verificationSummary,
                List.copyOf(nextFailureCategories)
            );
        }

        String failureDetail() {
            List<String> categories = failureCategories.stream()
                .filter(Objects::nonNull)
                .filter(category -> !category.isBlank())
                .distinct()
                .sorted()
                .toList();
            return categories.isEmpty() ? null : "llmFailureCategories=" + String.join(",", categories);
        }

        private static int safeInt(Integer value) {
            return value == null ? 0 : value;
        }
    }
}
