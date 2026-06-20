package com.repoguard.agent.worker;

import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.review.HumanReviewStatus;
import com.repoguard.agent.review.LlmStatus;
import com.repoguard.agent.review.ReviewResult;
import com.repoguard.agent.review.ReviewTaskStateMachine;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
class ReviewTaskCompletionApplier {

    private static final String HUMAN_REVIEW_THRESHOLD = "MEDIUM";

    private final ReviewTaskStateMachine reviewTaskStateMachine;

    ReviewTaskCompletionApplier(ReviewTaskStateMachine reviewTaskStateMachine) {
        this.reviewTaskStateMachine = reviewTaskStateMachine == null
            ? new ReviewTaskStateMachine()
            : reviewTaskStateMachine;
    }

    boolean applyCompleted(
        ReviewTask task,
        ReviewResult reviewResult,
        LocalDateTime startedAt,
        LocalDateTime finishedAt
    ) {
        boolean humanReviewRequired = requiresHumanReview(reviewResult.riskLevel());
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

    boolean requiresHumanReview(String riskLevel) {
        return riskRank(riskLevel) >= riskRank(HUMAN_REVIEW_THRESHOLD);
    }

    private int riskRank(String riskLevel) {
        if (riskLevel == null) {
            return 0;
        }
        return switch (riskLevel.toUpperCase(Locale.ROOT)) {
            case "CRITICAL" -> 5;
            case "HIGH" -> 4;
            case "MEDIUM" -> 3;
            case "LOW" -> 2;
            case "INFO" -> 1;
            default -> 0;
        };
    }
}
