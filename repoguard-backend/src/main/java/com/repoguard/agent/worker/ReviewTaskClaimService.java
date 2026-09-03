package com.repoguard.agent.worker;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.ReviewExecutionAttemptMapper;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.review.LlmStatus;
import com.repoguard.agent.review.ReviewTaskCheckRunLifecycle;
import com.repoguard.agent.review.ReviewTaskStateMachine;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ReviewTaskClaimService {

    private final ReviewTaskMapper reviewTaskMapper;
    private final ReviewTaskStateMachine reviewTaskStateMachine;
    private final ReviewExecutionAttemptMapper attemptMapper;
    private final ReviewTaskCheckRunLifecycle checkRunLifecycle;
    private final boolean useGenerationFence;

    @Autowired
    public ReviewTaskClaimService(
        ReviewTaskMapper reviewTaskMapper,
        ReviewTaskStateMachine reviewTaskStateMachine,
        ReviewExecutionAttemptMapper attemptMapper,
        ObjectProvider<ReviewTaskCheckRunLifecycle> checkRunLifecycleProvider
    ) {
        this(
            reviewTaskMapper,
            reviewTaskStateMachine,
            attemptMapper,
            checkRunLifecycleProvider.getIfAvailable(),
            true
        );
    }

    public ReviewTaskClaimService(
        ReviewTaskMapper reviewTaskMapper,
        ReviewTaskStateMachine reviewTaskStateMachine,
        ReviewExecutionAttemptMapper attemptMapper
    ) {
        this(reviewTaskMapper, reviewTaskStateMachine, attemptMapper, null, true);
    }

    private ReviewTaskClaimService(
        ReviewTaskMapper reviewTaskMapper,
        ReviewTaskStateMachine reviewTaskStateMachine,
        ReviewExecutionAttemptMapper attemptMapper,
        ReviewTaskCheckRunLifecycle checkRunLifecycle,
        boolean useGenerationFence
    ) {
        this.reviewTaskMapper = Objects.requireNonNull(reviewTaskMapper, "reviewTaskMapper");
        this.reviewTaskStateMachine = Objects.requireNonNull(reviewTaskStateMachine, "reviewTaskStateMachine");
        this.attemptMapper = Objects.requireNonNull(attemptMapper, "attemptMapper");
        this.checkRunLifecycle = checkRunLifecycle;
        this.useGenerationFence = useGenerationFence;
    }

    ReviewTaskClaimService(
        ReviewTaskMapper reviewTaskMapper,
        ReviewTaskStateMachine reviewTaskStateMachine
    ) {
        this.reviewTaskMapper = Objects.requireNonNull(reviewTaskMapper, "reviewTaskMapper");
        this.reviewTaskStateMachine = Objects.requireNonNull(reviewTaskStateMachine, "reviewTaskStateMachine");
        this.attemptMapper = null;
        this.checkRunLifecycle = null;
        this.useGenerationFence = false;
    }

    public String newClaimId() {
        return UUID.randomUUID().toString();
    }

    public boolean claimReviewing(ReviewTask task, LocalDateTime startedAt, String claimId) {
        int updated = useGenerationFence
            ? reviewTaskMapper.claimCurrentReview(task.getId(), startedAt, claimId)
            : reviewTaskMapper.update(
                new UpdateWrapper<ReviewTask>()
                    .eq("id", task.getId())
                    .eq("status", reviewTaskStateMachine.statusWhenQueued())
                    .set("status", reviewTaskStateMachine.statusWhenReviewing())
                    .set("started_at", startedAt)
                    .set("review_claimed_at", startedAt)
                    .set("review_claimed_by", claimId)
                    .set("publish_claimed_at", null)
                    .set("publish_claimed_by", null)
            );
        if (updated <= 0) {
            return false;
        }
        task.setStatus(reviewTaskStateMachine.statusWhenReviewing());
        task.setStartedAt(startedAt);
        task.setReviewClaimedAt(startedAt);
        task.setReviewClaimedBy(claimId);
        task.setPublishClaimedAt(null);
        task.setPublishClaimedBy(null);
        if (checkRunLifecycle != null) {
            checkRunLifecycle.inProgress(task);
        }
        return true;
    }

    public void releaseReviewClaim(ReviewTask task) {
        Objects.requireNonNull(task, "task");
        task.setReviewClaimedAt(null);
        task.setReviewClaimedBy(null);
    }

    public boolean writeTerminalStateIfClaimOwned(ReviewTask task, String claimId) {
        Objects.requireNonNull(task, "task");
        int updated = reviewTaskMapper.update(
            new UpdateWrapper<ReviewTask>()
                .eq("id", task.getId())
                .eq("status", reviewTaskStateMachine.statusWhenReviewing())
                .eq("review_claimed_by", claimId)
                .set("status", task.getStatus())
                .set("risk_level", task.getRiskLevel())
                .set("assessment_status", task.getAssessmentStatus())
                .set("llm_status", task.getLlmStatus())
                .set("llm_provider", task.getLlmProvider())
                .set("llm_model", task.getLlmModel())
                .set("llm_release_key", task.getLlmReleaseKey())
                .set("llm_duration_ms", task.getLlmDurationMs())
                .set("llm_parse_status", task.getLlmParseStatus())
                .set("llm_fallback_reason", task.getLlmFallbackReason())
                .set("llm_prompt_summary", task.getLlmPromptSummary())
                .set("llm_prompt_tokens", task.getLlmPromptTokens())
                .set("llm_completion_tokens", task.getLlmCompletionTokens())
                .set("llm_total_tokens", task.getLlmTotalTokens())
                .set("llm_estimated_cost", task.getLlmEstimatedCost())
                .set("human_review_required", task.getHumanReviewRequired())
                .set("human_review_status", task.getHumanReviewStatus())
                .set("human_review_note", task.getHumanReviewNote())
                .set("human_review_by", task.getHumanReviewBy())
                .set("human_reviewed_at", task.getHumanReviewedAt())
                .set("finished_at", task.getFinishedAt())
                .set("duration_seconds", task.getDurationSeconds())
                .set("review_claimed_at", null)
                .set("review_claimed_by", null)
        );
        if (updated <= 0) {
            return false;
        }
        if (checkRunLifecycle != null) {
            checkRunLifecycle.completed(task);
        }
        releaseReviewClaim(task);
        return true;
    }

    @Transactional
    public boolean markRequeuePendingIfClaimOwned(
        ReviewTask task,
        LocalDateTime expiredBefore,
        String recoveryReason
    ) {
        return markRequeuePendingIfClaimOwned(task, LocalDateTime.now(), expiredBefore, recoveryReason);
    }

    @Transactional
    public boolean markRequeuePendingIfClaimOwned(
        ReviewTask task,
        LocalDateTime recoveredAt,
        LocalDateTime expiredBefore,
        String recoveryReason
    ) {
        int updated = reviewTaskMapper.update(
            new UpdateWrapper<ReviewTask>()
                .eq("id", task.getId())
                .eq("status", reviewTaskStateMachine.statusWhenReviewing())
                .eq("review_claimed_by", task.getReviewClaimedBy())
                .le("review_claimed_at", expiredBefore)
                .set("status", reviewTaskStateMachine.statusWhenRequeuePending())
                .set("llm_status", LlmStatus.PENDING.code())
                .set("publish_attempts", 0)
                .set("next_publish_retry_at", null)
                .set("last_publish_error", recoveryReason)
                .set("review_claimed_at", null)
                .set("review_claimed_by", null)
        );
        if (updated <= 0) {
            return false;
        }
        if (attemptMapper != null && task.getCurrentAttemptId() != null) {
            attemptMapper.abandonRunningAttempt(
                task.getCurrentAttemptId(),
                task.getId(),
                task.getReviewClaimedBy(),
                "EXECUTION_LEASE_EXPIRED",
                recoveredAt
            );
        }
        task.setStatus(reviewTaskStateMachine.statusWhenRequeuePending());
        task.setLlmStatus(LlmStatus.PENDING.code());
        task.setPublishAttempts(0);
        task.setNextPublishRetryAt(null);
        task.setLastPublishError(recoveryReason);
        releaseReviewClaim(task);
        return true;
    }
}
