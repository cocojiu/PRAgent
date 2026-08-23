package com.repoguard.agent.worker;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.messaging.ReviewTaskPublishOutboxStore;
import com.repoguard.agent.review.ReviewTaskStateMachine;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
class ReviewTaskRecoveryStore {

    private final ReviewTaskMapper reviewTaskMapper;
    private final ReviewTaskStateMachine reviewTaskStateMachine;
    private final ReviewTaskClaimService claimService;
    private final ReviewTaskPublishOutboxStore outboxStore;

    ReviewTaskRecoveryStore(
        ReviewTaskMapper reviewTaskMapper,
        ReviewTaskStateMachine reviewTaskStateMachine,
        ReviewTaskClaimService claimService,
        ReviewTaskPublishOutboxStore outboxStore
    ) {
        this.reviewTaskMapper = reviewTaskMapper;
        this.reviewTaskStateMachine = Objects.requireNonNull(reviewTaskStateMachine, "reviewTaskStateMachine");
        this.claimService = Objects.requireNonNull(claimService, "claimService");
        this.outboxStore = Objects.requireNonNull(outboxStore, "outboxStore");
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
        return claimService.markRequeuePendingIfClaimOwned(task, recoveredAt, expiredBefore, recoveryReason);
    }

    boolean markQueuedForRecoveryPublish(ReviewTask task, LocalDateTime queuedAt, int nextAttempt) {
        return outboxStore.markQueuedForRecoveryPublish(task, nextAttempt);
    }

    boolean markRecoveryPublishFailed(
        ReviewTask task,
        LocalDateTime failedAt,
        LocalDateTime nextRetryAt,
        String error
    ) {
        return outboxStore.markRecoveryPublishFailed(task, nextRetryAt, error);
    }
}
