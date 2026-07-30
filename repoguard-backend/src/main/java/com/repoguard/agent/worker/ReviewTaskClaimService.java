package com.repoguard.agent.worker;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.review.LlmStatus;
import com.repoguard.agent.review.ReviewTaskStateMachine;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class ReviewTaskClaimService {

    private final ReviewTaskMapper reviewTaskMapper;
    private final ReviewTaskStateMachine reviewTaskStateMachine;

    public ReviewTaskClaimService(
        ReviewTaskMapper reviewTaskMapper,
        ReviewTaskStateMachine reviewTaskStateMachine
    ) {
        this.reviewTaskMapper = Objects.requireNonNull(reviewTaskMapper, "reviewTaskMapper");
        this.reviewTaskStateMachine = Objects.requireNonNull(reviewTaskStateMachine, "reviewTaskStateMachine");
    }

    public String newClaimId() {
        return UUID.randomUUID().toString();
    }

    public boolean claimReviewing(ReviewTask task, LocalDateTime startedAt, String claimId) {
        int updated = reviewTaskMapper.update(
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
                .set("llm_status", task.getLlmStatus())
                .set("llm_provider", task.getLlmProvider())
                .set("llm_model", task.getLlmModel())
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
        releaseReviewClaim(task);
        return true;
    }

    public boolean markRequeuePendingIfClaimOwned(
        ReviewTask task,
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
        task.setStatus(reviewTaskStateMachine.statusWhenRequeuePending());
        task.setLlmStatus(LlmStatus.PENDING.code());
        task.setPublishAttempts(0);
        task.setNextPublishRetryAt(null);
        task.setLastPublishError(recoveryReason);
        releaseReviewClaim(task);
        return true;
    }
}
