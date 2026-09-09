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
        return new ReviewPolicySettings(
            configured.exists(), configured.llmEnabled(), configured.llmProvider(), model.trim(),
            configured.baseUrl(), configured.apiKey(), configured.timeoutSeconds(), configured.temperature(),
            configured.maxTokens(), configured.fallbackToRules(), configured.workerConcurrency(),
            configured.chunkFileThreshold(), configured.chunkLineThreshold(), configured.chunkMaxFiles(),
            configured.chunkMaxLines(), configured.inputTokenPricePerMillion(),
            configured.outputTokenPricePerMillion(), configured.strategyRelease()
        );
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
        return reviewer.callChat(
            settings,
            promptBuilder.systemPrompt(),
            promptBuilder.buildPrompt(task, diff, context),
            "chat_completions",
            settings.maxTokens(),
            budget
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
        int maxTokens = Math.min(1_200, Math.max(256, settings.maxTokens() == null ? 1_200 : settings.maxTokens()));
        return reviewer.callChat(
            settings,
            promptBuilder.verificationSystemPrompt(),
            promptBuilder.buildVerificationPrompt(task, diff, candidate, context),
            "high_risk_verification",
            maxTokens,
            budget
        );
    }
}
