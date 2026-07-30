package com.repoguard.agent.worker;

import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.github.GithubPullRequestHeadChangedException;
import com.repoguard.agent.review.HumanReviewStatus;
import com.repoguard.agent.review.LlmStatus;
import com.repoguard.agent.review.AssessmentStatus;
import com.repoguard.agent.review.ReviewResult;
import com.repoguard.agent.review.ReviewTaskStateMachine;
import java.time.LocalDateTime;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
class ReviewTaskCompletionApplier {

    private final ReviewTaskStateMachine reviewTaskStateMachine;
    private final ReviewHumanReviewDecisionPolicy humanReviewDecisionPolicy;
    private final ReviewTaskFailureOutcomePolicy failureOutcomePolicy;
    private final ReviewTaskDurationPolicy durationPolicy;

    ReviewTaskCompletionApplier(
        ReviewTaskStateMachine reviewTaskStateMachine,
        ReviewHumanReviewDecisionPolicy humanReviewDecisionPolicy,
        ReviewTaskFailureOutcomePolicy failureOutcomePolicy,
        ReviewTaskDurationPolicy durationPolicy
    ) {
        this.reviewTaskStateMachine = Objects.requireNonNull(reviewTaskStateMachine, "reviewTaskStateMachine");
        this.humanReviewDecisionPolicy =
            Objects.requireNonNull(humanReviewDecisionPolicy, "humanReviewDecisionPolicy");
        this.failureOutcomePolicy = Objects.requireNonNull(failureOutcomePolicy, "failureOutcomePolicy");
        this.durationPolicy = Objects.requireNonNull(durationPolicy, "durationPolicy");
    }

    boolean applyCompleted(
        ReviewTask task,
        ReviewResult reviewResult,
        LocalDateTime startedAt,
        LocalDateTime finishedAt
    ) {
        boolean humanReviewRequired = humanReviewDecisionPolicy.requiresHumanReview(reviewResult);
        task.setStatus(reviewTaskStateMachine.statusAfterReviewCompleted(humanReviewRequired));
        task.setRiskLevel(reviewResult.riskLevel());
        task.setAssessmentStatus(AssessmentStatus.forCompletedReview(reviewResult).name());
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
        task.setDurationSeconds(durationPolicy.durationSeconds(startedAt, finishedAt));
        return humanReviewRequired;
    }

    void applyFailed(ReviewTask task, LocalDateTime startedAt, LocalDateTime failedAt) {
        task.setStatus(reviewTaskStateMachine.statusWhenFailed());
        task.setRiskLevel(failureOutcomePolicy.failedRiskLevel());
        task.setAssessmentStatus(AssessmentStatus.FAILED.name());
        task.setLlmStatus(failureOutcomePolicy.failedLlmStatus());
        task.setFinishedAt(failedAt);
        task.setDurationSeconds(durationPolicy.durationSeconds(startedAt, failedAt));
    }

    void applySuperseded(
        ReviewTask task,
        LocalDateTime startedAt,
        LocalDateTime supersededAt,
        GithubPullRequestHeadChangedException ex
    ) {
        task.setStatus(reviewTaskStateMachine.statusWhenSuperseded());
        task.setRiskLevel("INFO");
        task.setAssessmentStatus(AssessmentStatus.SUPERSEDED.name());
        task.setLlmStatus(LlmStatus.PENDING.code());
        task.setLlmProvider(null);
        task.setLlmModel(null);
        task.setLlmDurationMs(null);
        task.setLlmParseStatus(null);
        task.setLlmFallbackReason(ex.getMessage());
        task.setLlmPromptSummary(null);
        task.setLlmPromptTokens(null);
        task.setLlmCompletionTokens(null);
        task.setLlmTotalTokens(null);
        task.setLlmEstimatedCost(null);
        task.setHumanReviewRequired(false);
        task.setHumanReviewStatus(HumanReviewStatus.NOT_REQUIRED.code());
        task.setHumanReviewNote(null);
        task.setHumanReviewBy(null);
        task.setHumanReviewedAt(null);
        task.setFinishedAt(supersededAt);
        task.setDurationSeconds(durationPolicy.durationSeconds(startedAt, supersededAt));
    }

}
