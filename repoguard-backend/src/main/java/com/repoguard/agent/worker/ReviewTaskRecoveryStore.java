package com.repoguard.agent.worker;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.review.LlmStatus;
import com.repoguard.agent.review.ReviewTaskStateMachine;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
class ReviewTaskRecoveryStore {

    private final ReviewTaskMapper reviewTaskMapper;
    private final ReviewTaskStateMachine reviewTaskStateMachine;

    ReviewTaskRecoveryStore(ReviewTaskMapper reviewTaskMapper, ReviewTaskStateMachine reviewTaskStateMachine) {
        this.reviewTaskMapper = reviewTaskMapper;
        this.reviewTaskStateMachine = Objects.requireNonNull(reviewTaskStateMachine, "reviewTaskStateMachine");
    }

    List<ReviewTask> findExpiredReviewingTasks(LocalDateTime expiredBefore, int batchSize) {
        return reviewTaskMapper.selectList(
            new QueryWrapper<ReviewTask>()
                .eq("status", reviewTaskStateMachine.statusWhenReviewing())
                .isNotNull("review_claimed_at")
                .le("review_claimed_at", expiredBefore)
                .orderByAsc("review_claimed_at")
                .last("limit " + batchSize)
        );
    }

    boolean markRequeuePendingIfClaimOwned(
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
        task.setStatus(reviewTaskStateMachine.statusWhenRequeuePending());
        task.setLlmStatus(LlmStatus.PENDING.code());
        task.setPublishAttempts(0);
        task.setNextPublishRetryAt(null);
        task.setLastPublishError(recoveryReason);
        task.setReviewClaimedAt(null);
        task.setReviewClaimedBy(null);
        return true;
    }

    boolean markQueuedForRecoveryPublish(ReviewTask task, LocalDateTime queuedAt, int nextAttempt) {
        int updated = reviewTaskMapper.update(
            new UpdateWrapper<ReviewTask>()
                .eq("id", task.getId())
                .eq("status", reviewTaskStateMachine.statusWhenRequeuePending())
                .set("status", reviewTaskStateMachine.statusWhenQueued())
                .set("llm_status", LlmStatus.PENDING.code())
                .set("publish_attempts", nextAttempt)
                .set("next_publish_retry_at", null)
                .set("last_publish_error", null)
                .set("publish_claimed_at", null)
                .set("publish_claimed_by", null)
        );
        if (updated <= 0) {
            return false;
        }
        task.setStatus(reviewTaskStateMachine.statusWhenQueued());
        task.setLlmStatus(LlmStatus.PENDING.code());
        task.setPublishAttempts(nextAttempt);
        task.setNextPublishRetryAt(null);
        task.setLastPublishError(null);
        task.setPublishClaimedAt(null);
        task.setPublishClaimedBy(null);
        return true;
    }

    boolean markRecoveryPublishFailed(
        ReviewTask task,
        LocalDateTime failedAt,
        LocalDateTime nextRetryAt,
        String error
    ) {
        int updated = reviewTaskMapper.update(
            new UpdateWrapper<ReviewTask>()
                .eq("id", task.getId())
                .eq("status", reviewTaskStateMachine.statusWhenQueued())
                .set("status", reviewTaskStateMachine.statusWhenPublishFailed())
                .set("next_publish_retry_at", nextRetryAt)
                .set("last_publish_error", error)
                .set("publish_claimed_at", null)
                .set("publish_claimed_by", null)
        );
        if (updated <= 0) {
            return false;
        }
        task.setStatus(reviewTaskStateMachine.statusWhenPublishFailed());
        task.setNextPublishRetryAt(nextRetryAt);
        task.setLastPublishError(error);
        task.setPublishClaimedAt(null);
        task.setPublishClaimedBy(null);
        return true;
    }
}
