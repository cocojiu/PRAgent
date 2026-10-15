package com.repoguard.agent.review;

import com.repoguard.agent.entity.ReviewTask;
import java.util.Objects;

/** Routes evaluation-only review and verification calls through a shared hard budget. */
final class LlmEvaluationReviewCaller implements LlmReviewCaller {
    private final LlmPullRequestReviewer reviewer;
    private final LlmReviewPromptBuilder promptBuilder;
    private final LlmEvaluationBudget budget;

    LlmEvaluationReviewCaller(
        LlmPullRequestReviewer reviewer,
        LlmReviewPromptBuilder promptBuilder,
        LlmEvaluationBudget budget
    ) {
        this.reviewer = Objects.requireNonNull(reviewer, "reviewer");
        this.promptBuilder = Objects.requireNonNull(promptBuilder, "promptBuilder");
        this.budget = Objects.requireNonNull(budget, "budget");
    }

    static ReviewPolicySettings settings(ReviewPolicySettings configured, String provider, String model) {
        if (!configured.enabled() || !configured.readyForLlmReview()) {
            throw new IllegalStateException("LLM 评估运行需要已启用且配置完整的模型服务");
        }
        if (provider == null || provider.isBlank() || model == null || model.isBlank()
            || !provider.trim().equalsIgnoreCase(configured.llmProvider())) {
            throw new IllegalArgumentException("评估版本与当前 LLM 配置不一致");
        }
        return configured.withModel(model.trim());
    }

    @Override
    public LlmCallResult callLlm(ReviewPolicySettings settings, ReviewTask task, PullRequestDiff diff) {
        return callLlm(settings, task, diff, promptBuilder.buildContext(diff));
    }

    @Override
    public LlmCallResult callLlm(
        ReviewPolicySettings settings,
        ReviewTask task,
        PullRequestDiff diff,
        LlmReviewContext context
    ) {
        return callLlm(settings, task, diff, context, () -> Long.MAX_VALUE);
    }

    @Override
    public LlmCallResult callLlm(ReviewPolicySettings settings, ReviewTask task, PullRequestDiff diff,
        LlmReviewContext context, java.util.function.LongSupplier remaining) {
        return reviewer.callChat(
            task,
            settings,
            promptBuilder.systemPrompt(),
            promptBuilder.buildPrompt(task, diff, context),
            "chat_completions",
            settings.maxTokens(),
            budget,
            remaining
        );
    }

    @Override
    public boolean supportsHighRiskVerification() {
        return true;
    }

    @Override
    public LlmCallResult verifyHighRisk(
        ReviewPolicySettings settings,
        ReviewTask task,
        PullRequestDiff diff,
        ReviewFindingResult candidate,
        LlmReviewContext context
    ) {
        return verifyHighRisk(settings, task, diff, candidate, context, () -> Long.MAX_VALUE);
    }

    @Override
    public LlmCallResult verifyHighRisk(ReviewPolicySettings settings, ReviewTask task, PullRequestDiff diff,
        ReviewFindingResult candidate, LlmReviewContext context, java.util.function.LongSupplier remaining) {
        int maxTokens = Math.min(1_200, Math.max(256, settings.maxTokens() == null ? 1_200 : settings.maxTokens()));
        return reviewer.callChat(
            task,
            settings,
            promptBuilder.verificationSystemPrompt(),
            promptBuilder.buildVerificationPrompt(task, diff, candidate, context),
            "high_risk_verification",
            maxTokens,
            budget,
            remaining
        );
    }
}
