package com.repoguard.agent.worker;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.ReviewTaskMapper;
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

    boolean requeueIfClaimOwned(
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
                .set("status", reviewTaskStateMachine.statusWhenPublishFailed())
                .set("publish_attempts", 0)
                .set("next_publish_retry_at", recoveredAt)
                .set("last_publish_error", recoveryReason)
                .set("review_claimed_at", null)
                .set("review_claimed_by", null)
        );
        return updated > 0;
    }
}
