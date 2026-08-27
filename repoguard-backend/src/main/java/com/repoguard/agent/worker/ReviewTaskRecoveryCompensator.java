package com.repoguard.agent.worker;

import com.repoguard.agent.config.SchedulerRuntimeEnabled;
import com.repoguard.agent.concurrency.RecoveryWorkDispatcher;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.messaging.MessagePublishException;
import com.repoguard.agent.messaging.MessagePublishFailureSanitizer;
import com.repoguard.agent.messaging.RabbitPublishFailureClassifier;
import com.repoguard.agent.messaging.RabbitPublishFailurePhase;
import com.repoguard.agent.messaging.RabbitPublishFailureMetricsRecorder;
import com.repoguard.agent.review.task.ReviewTaskMessage;
import com.repoguard.agent.review.task.ReviewTaskPublisher;
import com.repoguard.agent.tenancy.ScheduledJobLeaseContext;
import com.repoguard.agent.observability.LogContext;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
@SchedulerRuntimeEnabled
public class ReviewTaskRecoveryCompensator {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReviewTaskRecoveryCompensator.class);
    private static final String RECOVERY_REASON = "Review execution lease expired";

    private final ReviewTaskRecoveryStore recoveryStore;
    private final ReviewTaskRecoveryTimelineRecorder timelineRecorder;
    private final ReviewExecutionClock clock;
    private final ReviewLogContextFormatter logContextFormatter;
    private final ReviewTaskRecoveryPolicy recoveryPolicy;
    private final ReviewTaskPublisher reviewTaskPublisher;
    private final RabbitPublishFailureMetricsRecorder metricsRecorder;
    private final RabbitPublishFailureClassifier failureClassifier;
    private final RecoveryWorkDispatcher recoveryWorkDispatcher;

    @Autowired
    public ReviewTaskRecoveryCompensator(
        ReviewTaskRecoveryStore recoveryStore,
        ReviewTaskRecoveryTimelineRecorder timelineRecorder,
        ReviewExecutionClock clock,
        ReviewLogContextFormatter logContextFormatter,
        ReviewTaskRecoveryPolicy recoveryPolicy,
        ReviewTaskPublisher reviewTaskPublisher,
        RabbitPublishFailureMetricsRecorder metricsRecorder,
        RabbitPublishFailureClassifier failureClassifier,
        RecoveryWorkDispatcher recoveryWorkDispatcher
    ) {
        this.recoveryStore = Objects.requireNonNull(recoveryStore, "recoveryStore");
        this.timelineRecorder = Objects.requireNonNull(timelineRecorder, "timelineRecorder");
        this.clock = clock;
        this.logContextFormatter = Objects.requireNonNull(logContextFormatter, "logContextFormatter");
        this.recoveryPolicy = Objects.requireNonNull(recoveryPolicy, "recoveryPolicy");
        this.reviewTaskPublisher = Objects.requireNonNull(reviewTaskPublisher, "reviewTaskPublisher");
        this.metricsRecorder = Objects.requireNonNull(metricsRecorder, "metricsRecorder");
        this.failureClassifier = Objects.requireNonNull(failureClassifier, "failureClassifier");
        this.recoveryWorkDispatcher = Objects.requireNonNull(
            recoveryWorkDispatcher,
            "recoveryWorkDispatcher"
        );
    }

    ReviewTaskRecoveryCompensator(
        ReviewTaskRecoveryStore recoveryStore,
        ReviewTaskRecoveryTimelineRecorder timelineRecorder,
        ReviewExecutionClock clock,
        ReviewLogContextFormatter logContextFormatter,
        ReviewTaskRecoveryPolicy recoveryPolicy,
        ReviewTaskPublisher reviewTaskPublisher,
        RabbitPublishFailureMetricsRecorder metricsRecorder,
        RabbitPublishFailureClassifier failureClassifier
    ) {
        this(
            recoveryStore,
            timelineRecorder,
            clock,
            logContextFormatter,
            recoveryPolicy,
            reviewTaskPublisher,
            metricsRecorder,
            failureClassifier,
            new RecoveryWorkDispatcher(Runnable::run)
        );
    }

    public void recoverStuckTasks() {
        LocalDateTime now = clock.now();
        LocalDateTime expiredBefore = recoveryPolicy.expiredBefore(now);
        List<ReviewTask> tasks = recoveryStore.findExpiredReviewingTasks(expiredBefore, recoveryPolicy.batchSize());
        for (ReviewTask task : tasks) {
            ScheduledJobLeaseContext.assertHeld();
            if (!recoveryWorkDispatcher.submit(
                "review_execution_recovery",
                () -> recover(task, now, expiredBefore)
            )) {
                LOGGER.warn(
                    "Review task recovery deferred taskId={} operation=review_recovery result=executor_rejected",
                    task.getId()
                );
            }
        }
    }

    void recover(ReviewTask task, LocalDateTime recoveredAt, LocalDateTime expiredBefore) {
        try (LogContext.Scope _ = LogContext.withReviewTask(task)) {
            if (!recoveryStore.markRequeuePendingIfClaimOwned(task, recoveredAt, expiredBefore, RECOVERY_REASON)) {
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
            timelineRecorder.requeuePending(task, recoveredAt);
            int nextAttempt = safeAttempts(task) + 1;
            if (!recoveryStore.markQueuedForRecoveryPublish(task, recoveredAt, nextAttempt)) {
                LOGGER.info(
                    "Review task recovery skipped taskId={} repository={} prNumber={} operation=review_recovery result=queue_claim_lost expiredBefore={}",
                    task.getId(),
                    logContextFormatter.repositorySlug(task),
                    task.getPrNumber(),
                    expiredBefore
                );
                return;
            }
            try {
                reviewTaskPublisher.publishOnce(toMessage(task, recoveredAt));
            } catch (MessagePublishException ex) {
                String error = truncate(MessagePublishFailureSanitizer.sanitize(ex));
                LocalDateTime nextRetryAt = recoveredAt.plusNanos(recoveryPolicy.publishRetryDelayMs() * 1_000_000);
                if (recoveryStore.markRecoveryPublishFailed(task, recoveredAt, nextRetryAt, error)) {
                    timelineRecorder.recoveryPublishFailed(task, recoveredAt, error);
                    metricsRecorder.recordFailed(RabbitPublishFailurePhase.EXECUTE, failureClassifier.classify(ex));
                    LOGGER.warn(
                        "Review task recovery publish failed taskId={} repository={} prNumber={} operation=review_recovery result=publish_failed attempts={} nextRetryAt={} error={}",
                        task.getId(),
                        logContextFormatter.repositorySlug(task),
                        task.getPrNumber(),
                        safeAttempts(task),
                        nextRetryAt,
                        error
                    );
                }
                return;
            }
            timelineRecorder.recoveryQueued(task, recoveredAt);
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

    private ReviewTaskMessage toMessage(ReviewTask task, LocalDateTime queuedAt) {
        return new ReviewTaskMessage(
            task.getId(),
            task.getOrganization(),
            task.getRepository(),
            task.getPrNumber(),
            task.getCommitSha(),
            queuedAt,
            LogContext.currentTraceId(),
            3
        );
    }

    private int safeAttempts(ReviewTask task) {
        return task.getPublishAttempts() == null ? 0 : task.getPublishAttempts();
    }

    private String truncate(String value) {
        return value.length() > 120 ? value.substring(0, 117) + "..." : value;
    }
}
