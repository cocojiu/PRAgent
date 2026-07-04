package com.repoguard.agent.worker;

import com.repoguard.agent.config.WorkerRuntimeEnabled;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.observability.LogContext;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@WorkerRuntimeEnabled
public class ReviewTaskRecoveryCompensator {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReviewTaskRecoveryCompensator.class);
    private static final String RECOVERY_REASON = "Review execution lease expired";

    private final ReviewTaskRecoveryStore recoveryStore;
    private final ReviewTimelineAppender timelineAppender;
    private final ReviewExecutionClock clock;
    private final ReviewLogContextFormatter logContextFormatter;
    private final ReviewTaskRecoveryPolicy recoveryPolicy;

    public ReviewTaskRecoveryCompensator(
        ReviewTaskRecoveryStore recoveryStore,
        ReviewTimelineAppender timelineAppender,
        ReviewExecutionClock clock,
        ReviewLogContextFormatter logContextFormatter,
        ReviewTaskRecoveryPolicy recoveryPolicy
    ) {
        this.recoveryStore = Objects.requireNonNull(recoveryStore, "recoveryStore");
        this.timelineAppender = timelineAppender;
        this.clock = clock;
        this.logContextFormatter = Objects.requireNonNull(logContextFormatter, "logContextFormatter");
        this.recoveryPolicy = Objects.requireNonNull(recoveryPolicy, "recoveryPolicy");
    }

    @Scheduled(fixedDelayString = "${app.rabbit.review.review-recovery-interval-ms:60000}")
    @Transactional
    public void recoverStuckTasks() {
        LocalDateTime now = clock.now();
        LocalDateTime expiredBefore = recoveryPolicy.expiredBefore(now);
        List<ReviewTask> tasks = recoveryStore.findExpiredReviewingTasks(expiredBefore, recoveryPolicy.batchSize());
        for (ReviewTask task : tasks) {
            recover(task, now, expiredBefore);
        }
    }

    void recover(ReviewTask task, LocalDateTime recoveredAt, LocalDateTime expiredBefore) {
        try (LogContext.Scope ignored = LogContext.withReviewTask(task)) {
            if (!recoveryStore.requeueIfClaimOwned(task, recoveredAt, expiredBefore, RECOVERY_REASON)) {
                LOGGER.info(
                    "Review task recovery skipped taskId={} repository={} prNumber={} operation=review_recovery result=claim_lost claimedAt={} claimedBy={} expiredBefore={}",
                    task.getId(),
                    logContextFormatter.repositorySlug(task),
                    task.getPrNumber(),
                    task.getReviewClaimedAt(),
                    task.getReviewClaimedBy(),
                    expiredBefore
                );
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
                "Review task recovery completed taskId={} repository={} prNumber={} operation=review_recovery result=requeued claimedAt={} claimedBy={} expiredBefore={} nextRetryAt={}",
                task.getId(),
                logContextFormatter.repositorySlug(task),
                task.getPrNumber(),
                task.getReviewClaimedAt(),
                task.getReviewClaimedBy(),
                expiredBefore,
                recoveredAt
            );
        }
    }
}
