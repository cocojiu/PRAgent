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
        this.metrics = metrics;
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must be provided");
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

            List<ReviewFindingResult> findings = new java.util.ArrayList<>();
            String riskLevel = "INFO";
            int promptTokens = 0;
            int completionTokens = 0;
            int totalTokens = 0;
            int failedChunks = 0;
            for (PullRequestDiffChunk chunk : chunks) {
                try {
                    LlmCallResult callResult = context.llmReviewCaller().callLlm(settings, context.task(), chunk.diff());
                    ReviewResult parsed = qualityScorer.score(reviewResultParser.parse(callResult.content()), chunk.diff());
                    riskLevel = reviewMerger.maxRisk(riskLevel, parsed.riskLevel());
                    promptTokens += safeInt(callResult.promptTokens());
                    completionTokens += safeInt(callResult.completionTokens());
                    totalTokens += safeInt(callResult.totalTokens());
                    if (parsed.findings() != null) {
                        findings.addAll(parsed.findings());
                    }
                } catch (RuntimeException ex) {
                    failedChunks++;
                    if (metrics != null) {
                        metrics.llmFallback("chunk_partial_failure");
                    }
                    ReviewResult ruleReview = ruleBasedReviewer.review(chunk.diff());
                    riskLevel = reviewMerger.maxRisk(riskLevel, ruleReview.riskLevel());
                    if (ruleReview.findings() != null) {
                        findings.addAll(ruleReview.findings());
                    }
                }
            }
            return ReviewResult.completed(
                riskLevel,
                findings,
                null,
                null,
                null,
                failedChunks > 0 ? "partial_fallback" : null,
                promptBuilder.chunkedPromptSummary(diff, chunks, findings.size(), riskLevel, failedChunks),
                zeroToNull(promptTokens),
                zeroToNull(completionTokens),
                zeroToNull(totalTokens),
                costEstimator.estimate(settings, zeroToNull(promptTokens), zeroToNull(completionTokens))
            );
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
            metrics.llmFallback(reasonCategory(reason));
        }
        ReviewResult fallback = ruleBasedReviewer.review(context.diff());
        ReviewPolicySettings settings = context.settings();
        return ReviewResult.fallback(
            fallback.riskLevel(),
            normalizeReason(reason),
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

    private String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "LLM review unavailable";
        }
        return reason.replaceAll("\\s+", " ").trim();
    }

    private String reasonCategory(String reason) {
        String normalized = normalizeReason(reason).toLowerCase();
        int markerIndex = normalized.indexOf("category=");
        if (markerIndex >= 0) {
            int valueStart = markerIndex + "category=".length();
            int valueEnd = normalized.indexOf(' ', valueStart);
            return valueEnd < 0 ? normalized.substring(valueStart) : normalized.substring(valueStart, valueEnd);
        }
        if (normalized.contains("config")) {
            return "config_incomplete";
        }
        return "llm_unavailable";
    }

    private Integer elapsedMillis(long startedAt) {
        long elapsed = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
        return elapsed > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) elapsed;
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }

    private Integer zeroToNull(int value) {
        return value <= 0 ? null : value;
    }

}
