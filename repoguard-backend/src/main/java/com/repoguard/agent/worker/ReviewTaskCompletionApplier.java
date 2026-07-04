package com.repoguard.agent.worker;

import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.review.HumanReviewStatus;
import com.repoguard.agent.review.LlmStatus;
import com.repoguard.agent.review.ReviewResult;
import com.repoguard.agent.review.ReviewTaskStateMachine;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
class ReviewTaskCompletionApplier {

    private final ReviewTaskStateMachine reviewTaskStateMachine;
    private final ReviewHumanReviewDecisionPolicy humanReviewDecisionPolicy;

    ReviewTaskCompletionApplier(
        ReviewTaskStateMachine reviewTaskStateMachine,
        ReviewHumanReviewDecisionPolicy humanReviewDecisionPolicy
    ) {
        this.reviewTaskStateMachine = Objects.requireNonNull(reviewTaskStateMachine, "reviewTaskStateMachine");
        this.humanReviewDecisionPolicy =
            Objects.requireNonNull(humanReviewDecisionPolicy, "humanReviewDecisionPolicy");
    }

    boolean applyCompleted(
        ReviewTask task,
        ReviewResult reviewResult,
        LocalDateTime startedAt,
        LocalDateTime finishedAt
    ) {
        boolean humanReviewRequired = humanReviewDecisionPolicy.requiresHumanReview(reviewResult.riskLevel());
        task.setStatus(reviewTaskStateMachine.statusAfterReviewCompleted(humanReviewRequired));
        task.setRiskLevel(reviewResult.riskLevel());
        task.setLlmStatus(reviewResult.llmStatus());
        task.setLlmProvider(reviewResult.llmProvider());
        task.setLlmModel(reviewResult.llmModel());
        task.setLlmDurationMs(reviewResult.llmDurationMs());
        task.setLlmParseStatus(reviewResult.llmParseStatus());
        task.setLlmFallbackReason(reviewResult.statusDetail());
        task.setLlmPromptSummary(reviewResult.llmPromptSummary());
        task.setLlmPromptTokens(reviewResult.llmPromptTokens());
        task.setLlmCompletionTokens(reviewResult.llmCompletionTokens());
        task.setLlmTotalTokens(reviewResult.llmTotalTokens());
        task.setLlmEstimatedCost(reviewResult.llmEstimatedCost());
        task.setHumanReviewRequired(humanReviewRequired);
        task.setHumanReviewStatus(HumanReviewStatus.defaultForRequired(humanReviewRequired).code());
        task.setHumanReviewNote(null);
        task.setHumanReviewBy(null);
        task.setHumanReviewedAt(null);
        task.setFinishedAt(finishedAt);
        task.setDurationSeconds((int) Duration.between(startedAt, finishedAt).toSeconds());
        return humanReviewRequired;
    }

    void applyFailed(ReviewTask task, LocalDateTime startedAt, LocalDateTime failedAt) {
        task.setStatus(reviewTaskStateMachine.statusWhenFailed());
        task.setRiskLevel("HIGH");
        task.setLlmStatus(LlmStatus.FAILED.code());
        task.setFinishedAt(failedAt);
        task.setDurationSeconds((int) Duration.between(startedAt, failedAt).toSeconds());
    }

}
