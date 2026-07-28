package com.repoguard.agent.review;

import com.repoguard.agent.config.ReviewPolicySettings;
import com.repoguard.agent.external.ExternalCallException;
import com.repoguard.agent.github.GithubPullRequestDiff;
import com.repoguard.agent.observability.RepoGuardMetrics;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
class LlmReviewPipeline {

    private static final Logger LOGGER = LoggerFactory.getLogger(LlmReviewPipeline.class);

    private final List<ReviewPipelineStage> stages;
    private final RuleBasedPullRequestReviewer ruleBasedReviewer;
    private final LlmReviewPromptBuilder promptBuilder;
    private final LlmRuleReviewMerger reviewMerger;
    private final LlmReviewQualityScorer qualityScorer;
    private final LlmReviewCostEstimator costEstimator;
    private final LlmChunkReviewAggregator chunkReviewAggregator;
    private final LlmFallbackReasonClassifier fallbackReasonClassifier;
    private final LlmReviewResultParser reviewResultParser;
    private final RepoGuardMetrics metrics;
    private final Duration pipelineBudget;
    private final Executor llmChunkExecutor;

    @Autowired
    LlmReviewPipeline(
        RuleBasedPullRequestReviewer ruleBasedReviewer,
        LlmReviewPromptBuilder promptBuilder,
        LlmRuleReviewMerger reviewMerger,
        LlmReviewQualityScorer qualityScorer,
        LlmReviewCostEstimator costEstimator,
        LlmReviewResultParser reviewResultParser,
        RepoGuardMetrics metrics,
        LlmFallbackReasonClassifier fallbackReasonClassifier,
        PullRequestDiffChunker diffChunker,
        @Qualifier(LlmChunkReviewExecutorConfig.LLM_CHUNK_REVIEW_EXECUTOR) Executor llmChunkExecutor,
        ReviewPipelineBudgetProperties budgetProperties
    ) {
        ReviewPipelineBudgetProperties pipelineProperties = Objects.requireNonNull(
            budgetProperties,
            "budgetProperties"
        );
        this.pipelineBudget = Duration.ofMillis(pipelineProperties.getBudgetMs());
        this.ruleBasedReviewer = Objects.requireNonNull(ruleBasedReviewer, "ruleBasedReviewer");
        this.promptBuilder = Objects.requireNonNull(promptBuilder, "promptBuilder");
        this.reviewMerger = Objects.requireNonNull(reviewMerger, "reviewMerger");
        this.qualityScorer = Objects.requireNonNull(qualityScorer, "qualityScorer");
        this.costEstimator = Objects.requireNonNull(costEstimator, "costEstimator");
        this.fallbackReasonClassifier = Objects.requireNonNull(fallbackReasonClassifier, "fallbackReasonClassifier");
        this.reviewResultParser = Objects.requireNonNull(reviewResultParser, "reviewResultParser");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.llmChunkExecutor = Objects.requireNonNull(llmChunkExecutor, "llmChunkExecutor");
        this.chunkReviewAggregator = new LlmChunkReviewAggregator(
            this.ruleBasedReviewer,
            this.promptBuilder,
            this.reviewMerger,
            this.qualityScorer,
            this.costEstimator,
            metrics,
            this.llmChunkExecutor,
            pipelineProperties.getMaxTotalChunks(),
            pipelineProperties.getMaxInFlightChunks()
        );
        this.stages = List.of(
            new LlmReadinessStage(),
            new LlmExecutionStage(Objects.requireNonNull(diffChunker, "diffChunker")),
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
                return fallbackReview(context, ex);
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

        LlmExecutionStage(PullRequestDiffChunker diffChunker) {
            this.diffChunker = diffChunker;
        }

        @Override
        public ReviewPipelineState apply(ReviewPipelineState state) {
            return state.withLlmReview(reviewWithOptionalChunks(state.context()));
        }

        private ReviewResult reviewWithOptionalChunks(ReviewPipelineContext context) {
            ReviewPolicySettings settings = context.settings();
            GithubPullRequestDiff diff = context.diff();
            ReviewBudget budget = ReviewBudget.startingAt(context.startedAtNanos(), pipelineBudget);
            List<PullRequestDiffChunk> chunks = diffChunker.chunk(diff, settings);
            if (chunks.size() == 1) {
                LlmCallResult callResult = callSingleChunk(context, settings, diff, budget);
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

            return chunkReviewAggregator.aggregate(
                context,
                diff,
                chunks,
                reviewResultParser,
                budget
            );
        }
    }

    private LlmCallResult callSingleChunk(
        ReviewPipelineContext context,
        ReviewPolicySettings settings,
        GithubPullRequestDiff diff,
        ReviewBudget budget
    ) {
        if (budget.exhausted()) {
            throw pipelineUnavailable(
                LlmChunkReviewAggregator.BUDGET_EXHAUSTED_CATEGORY,
                "pipeline budget exhausted before the LLM call started",
                null
            );
        }
        FutureTask<LlmCallResult> call = new FutureTask<>(
            () -> context.llmReviewCaller().callLlm(settings, context.task(), diff)
        );
        try {
            llmChunkExecutor.execute(call);
        } catch (RejectedExecutionException ex) {
            throw pipelineUnavailable(
                LlmChunkReviewAggregator.EXECUTOR_REJECTED_CATEGORY,
                "LLM executor rejected the single chunk",
                ex
            );
        }

        long remainingNanos = budget.remainingNanos();
        if (remainingNanos <= 0) {
            call.cancel(true);
            throw pipelineUnavailable(
                LlmChunkReviewAggregator.BUDGET_EXHAUSTED_CATEGORY,
                "pipeline budget exhausted while the LLM call was queued",
                null
            );
        }
        try {
            return call.get(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException ex) {
            call.cancel(true);
            throw pipelineUnavailable(
                LlmChunkReviewAggregator.BUDGET_EXHAUSTED_CATEGORY,
                "pipeline budget exhausted while waiting for the LLM call",
                ex
            );
        } catch (ExecutionException ex) {
            throw ex.getCause() instanceof RuntimeException cause ? cause : new CompletionException(ex.getCause());
        } catch (InterruptedException ex) {
            call.cancel(true);
            Thread.currentThread().interrupt();
            throw new CompletionException(ex);
        }
    }

    private ExternalCallException pipelineUnavailable(String category, String detail, Throwable cause) {
        return new ExternalCallException("LLM", category, true, null, detail, cause);
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
                parsed.llmParseStatus() == null ? LlmParseStatus.PARSED.code() : parsed.llmParseStatus(),
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

    private ReviewResult fallbackReview(ReviewPipelineContext context, RuntimeException failure) {
        if (failure instanceof ExternalCallException) {
            LOGGER.warn(
                "LLM review fell back to rules taskId={} operation=llm_review result=fallback exceptionType={} reason={}",
                taskId(context),
                failure.getClass().getName(),
                failure.getMessage()
            );
        } else {
            LOGGER.error(
                "LLM review failed with internal error taskId={} operation=llm_review result=fallback exceptionType={}",
                taskId(context),
                failure.getClass().getName(),
                failure
            );
        }
        return fallbackReview(
            context,
            fallbackReasonClassifier.category(failure),
            fallbackReasonClassifier.normalizeReason(failure)
        );
    }

    private ReviewResult fallbackReview(ReviewPipelineContext context, String reason) {
        return fallbackReview(
            context,
            fallbackReasonClassifier.category(reason),
            fallbackReasonClassifier.normalizeReason(reason)
        );
    }

    private ReviewResult fallbackReview(ReviewPipelineContext context, String category, String reason) {
        metrics.llmFallback(category);
        ReviewResult fallback = ruleBasedReviewer.review(context.diff());
        ReviewPolicySettings settings = context.settings();
        return ReviewResult.fallback(
            fallback.riskLevel(),
            reason,
            fallback.findings(),
            settings == null ? null : settings.llmProvider(),
            settings == null ? null : settings.modelName(),
            elapsedMillis(context.startedAtNanos()),
            context.promptSummary()
        );
    }

    private Long taskId(ReviewPipelineContext context) {
        return context.task() == null ? null : context.task().getId();
    }

    private boolean isLlmReady(ReviewPolicySettings settings) {
        return settings != null && settings.exists() && settings.enabled() && settings.readyForLlmReview();
    }

    private Integer elapsedMillis(long startedAt) {
        long elapsed = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
        return elapsed > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) elapsed;
    }

}
