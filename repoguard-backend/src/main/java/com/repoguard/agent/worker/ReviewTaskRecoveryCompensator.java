package com.repoguard.agent.worker;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.repoguard.agent.config.RabbitReviewQueueProperties;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.review.ReviewTaskStateMachine;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ReviewTaskRecoveryCompensator {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReviewTaskRecoveryCompensator.class);
    private static final String RECOVERY_REASON = "Review execution lease expired";

    private final ReviewTaskMapper reviewTaskMapper;
    private final ReviewTimelineAppender timelineAppender;
    private final RabbitReviewQueueProperties properties;
    private final ReviewTaskStateMachine reviewTaskStateMachine;

    public ReviewTaskRecoveryCompensator(
        ReviewTaskMapper reviewTaskMapper,
        ReviewTimelineAppender timelineAppender,
        RabbitReviewQueueProperties properties,
        ReviewTaskStateMachine reviewTaskStateMachine
    ) {
        this.reviewTaskMapper = reviewTaskMapper;
        this.timelineAppender = timelineAppender;
        this.properties = properties;
        this.reviewTaskStateMachine = reviewTaskStateMachine;
    }

    @Scheduled(fixedDelayString = "${app.rabbit.review.review-recovery-interval-ms:60000}")
    @Transactional
    public void recoverStuckTasks() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiredBefore = now.minusNanos(executionTimeoutMs() * 1_000_000);
        List<ReviewTask> tasks = reviewTaskMapper.selectList(
            new LambdaQueryWrapper<ReviewTask>()
                .eq(ReviewTask::getStatus, reviewTaskStateMachine.statusWhenReviewing())
                .isNotNull(ReviewTask::getReviewClaimedAt)
                .le(ReviewTask::getReviewClaimedAt, expiredBefore)
                .orderByAsc(ReviewTask::getReviewClaimedAt)
                .last("limit " + batchSize())
        );
        for (ReviewTask task : tasks) {
            recover(task, now, expiredBefore);
        }
    }

    void recover(ReviewTask task, LocalDateTime recoveredAt, LocalDateTime expiredBefore) {
        int updated = reviewTaskMapper.update(
            new UpdateWrapper<ReviewTask>()
                .eq("id", task.getId())
                .eq("status", reviewTaskStateMachine.statusWhenReviewing())
                .eq("review_claimed_by", task.getReviewClaimedBy())
                .le("review_claimed_at", expiredBefore)
                .set("status", reviewTaskStateMachine.statusWhenPublishFailed())
                .set("publish_attempts", 0)
                .set("next_publish_retry_at", recoveredAt)
                .set("last_publish_error", RECOVERY_REASON)
                .set("review_claimed_at", null)
                .set("review_claimed_by", null)
        );
        if (updated <= 0) {
            return;
        }
        timelineAppender.append(
            task.getId(),
            "Review execution timed out; queued for recovery",
            recoveredAt,
            "CURRENT",
            5
        );
        LOGGER.warn(
            "Stuck review task queued for recovery taskId={} operation=review_recovery result=requeued claimedAt={}",
            task.getId(),
            task.getReviewClaimedAt()
        );
    }

    private long executionTimeoutMs() {
        return Math.max(60000, properties.getReviewExecutionTimeoutMs());
    }

    private int batchSize() {
        return Math.max(1, properties.getReviewRecoveryBatchSize());
    }
}
