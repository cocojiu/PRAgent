package com.repoguard.agent.messaging;

import com.repoguard.agent.config.WorkerRuntimeEnabled;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.observability.LogContext;
import com.repoguard.agent.observability.RepoGuardMetrics;
import com.repoguard.agent.review.ReviewTaskStateMachine;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@WorkerRuntimeEnabled
public class ReviewTaskPublishCompensator {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReviewTaskPublishCompensator.class);

    private final ReviewTaskPublisher reviewTaskPublisher;
    private final ReviewTaskPublishOutboxStore outboxStore;
    private final ReviewTaskPublishCompensationQuery compensationQuery;
    private final String instanceId;
    private final RepoGuardMetrics metrics;
    private final ReviewTaskStateMachine reviewTaskStateMachine;
    private final RabbitPublishFailureClassifier failureClassifier;

    @Autowired
    public ReviewTaskPublishCompensator(
        ReviewTaskPublisher reviewTaskPublisher,
        RepoGuardMetrics metrics,
        ReviewTaskPublishOutboxStore outboxStore,
        ReviewTaskPublishCompensationQuery compensationQuery,
        ReviewTaskStateMachine reviewTaskStateMachine,
        RabbitPublishFailureClassifier failureClassifier
    ) {
        this(
            reviewTaskPublisher,
            "repoguard-" + UUID.randomUUID(),
            metrics,
            outboxStore,
            compensationQuery,
            reviewTaskStateMachine,
            failureClassifier
        );
    }

    ReviewTaskPublishCompensator(
        ReviewTaskPublisher reviewTaskPublisher,
        String instanceId,
        RepoGuardMetrics metrics,
        ReviewTaskPublishOutboxStore outboxStore,
        ReviewTaskPublishCompensationQuery compensationQuery,
        ReviewTaskStateMachine reviewTaskStateMachine,
        RabbitPublishFailureClassifier failureClassifier
    ) {
        this.reviewTaskPublisher = Objects.requireNonNull(reviewTaskPublisher, "reviewTaskPublisher");
        this.outboxStore = Objects.requireNonNull(outboxStore, "outboxStore");
        this.compensationQuery = Objects.requireNonNull(compensationQuery, "compensationQuery");
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.reviewTaskStateMachine = Objects.requireNonNull(reviewTaskStateMachine, "reviewTaskStateMachine");
        this.failureClassifier = Objects.requireNonNull(failureClassifier, "failureClassifier");
    }

    @Scheduled(fixedDelayString = "${app.rabbit.review.publish-compensation-interval-ms:60000}")
    public void compensatePublishFailures() {
        LocalDateTime now = LocalDateTime.now();
        List<ReviewTask> tasks = compensationQuery.loadDueTasks(now);
        for (ReviewTask task : tasks) {
            compensate(task);
        }
    }

    void compensate(ReviewTask task) {
        try (LogContext.Scope ignored = LogContext.withReviewTask(task)) {
            LocalDateTime claimedAt = LocalDateTime.now();
            RabbitPublishClaim claim = compensationQuery.claim(claimedAt, instanceId);
            String recoverySource = recoverySource(task);
            if (!claimTask(task, claim)) {
                LOGGER.info(
                    "Review task publish compensation skipped taskId={} repository={} prNumber={} operation=review_publish_compensation result=claim_failed status={} attempts={} maxAttempts={}",
                    task.getId(),
                    repositorySlug(task),
                    task.getPrNumber(),
                    task.getStatus(),
                    safeAttempts(task),
                    compensationQuery.maxAttempts()
                );
                return;
            }
            int nextAttempt = compensationQuery.nextAttempt(task.getPublishAttempts());
            LOGGER.info(
                "Review task publish compensation claimed taskId={} repository={} prNumber={} operation=review_publish_compensation recoverySource={} currentStatus={} nextAttempt={} maxAttempts={} claimedAt={}",
                task.getId(),
                repositorySlug(task),
                task.getPrNumber(),
                recoverySource,
                task.getStatus(),
                nextAttempt,
                compensationQuery.maxAttempts(),
                claimedAt
            );
            if (!markQueuedBeforePublish(task, claim, nextAttempt)) {
                LOGGER.warn(
                    "Review task publish compensation skipped taskId={} repository={} prNumber={} operation=review_publish_compensation result=mark_queued_failed recoverySource={} claimedAt={}",
                    task.getId(),
                    repositorySlug(task),
                    task.getPrNumber(),
                    recoverySource,
                    claimedAt
                );
                return;
            }
            try {
                reviewTaskPublisher.publish(toMessage(task, LocalDateTime.now()));
                outboxStore.clearPublishClaim(task, claim);
                outboxStore.appendTimeline(task.getId(), "Message publish recovered", LocalDateTime.now(), "CURRENT");
                metrics.rabbitPublishCompensationSucceeded("publish");
                LOGGER.info(
                    "Review task publish compensation completed taskId={} repository={} prNumber={} operation=review_publish_compensation result=published recoverySource={} attempts={}",
                    task.getId(),
                    repositorySlug(task),
                    task.getPrNumber(),
                    recoverySource,
                    safeAttempts(task)
                );
            } catch (MessagePublishException ex) {
                String errorMessage = errorMessage(ex);
                if (markPublishFailed(task, claim, ex)) {
                    outboxStore.appendTimeline(
                        task.getId(),
                        "Message publish retry failed: " + truncate(errorMessage),
                        LocalDateTime.now(),
                        "FAILED"
                    );
                    metrics.rabbitPublishCompensationFailed(failureClassifier.classify(ex));
                    LOGGER.warn(
                        "Review task publish compensation failed taskId={} repository={} prNumber={} operation=review_publish_compensation result=publish_failed recoverySource={} attempts={} nextRetryAt={} error={}",
                        task.getId(),
                        repositorySlug(task),
                        task.getPrNumber(),
                        recoverySource,
                        safeAttempts(task),
                        task.getNextPublishRetryAt(),
                        errorMessage
                    );
                    return;
                }
                LOGGER.warn(
                    "Review task publish compensation failure discarded taskId={} repository={} prNumber={} operation=review_publish_compensation result=claim_lost recoverySource={} error={}",
                    task.getId(),
                    repositorySlug(task),
                    task.getPrNumber(),
                    recoverySource,
                    errorMessage
                );
            }
        }
    }

    private boolean claimTask(ReviewTask task, RabbitPublishClaim claim) {
        return outboxStore.claimForPublish(task, claim);
    }

    private String recoverySource(ReviewTask task) {
        if (reviewTaskStateMachine.isPublishFailed(task.getStatus())) {
            return "publish_failed";
        }
        if (reviewTaskStateMachine.isExecutionTimeout(task.getStatus())) {
            return "execution_timeout";
        }
        return task.getPublishClaimedAt() == null
            ? "stale_unclaimed_queued"
            : "stale_queued_claim";
    }

    private boolean markQueuedBeforePublish(ReviewTask task, RabbitPublishClaim claim, int nextAttempt) {
        return outboxStore.markQueuedForPublish(task, claim, nextAttempt);
    }

    private boolean markPublishFailed(ReviewTask task, RabbitPublishClaim claim, MessagePublishException ex) {
        LocalDateTime nextRetryAt = compensationQuery.nextRetryAt(LocalDateTime.now());
        String error = truncate(errorMessage(ex));
        return outboxStore.markClaimedPublishFailed(task, claim, nextRetryAt, error);
    }

    private ReviewTaskMessage toMessage(ReviewTask task, LocalDateTime queuedAt) {
        return new ReviewTaskMessage(
            task.getId(),
            task.getOrganization(),
            task.getRepository(),
            task.getPrNumber(),
            task.getCommitSha(),
            queuedAt,
            LogContext.currentTraceId()
        );
    }

    private int safeAttempts(ReviewTask task) {
        return task.getPublishAttempts() == null ? 0 : task.getPublishAttempts();
    }

    private String repositorySlug(ReviewTask task) {
        return safePart(task.getOrganization()) + "/" + safePart(task.getRepository());
    }

    private String safePart(String value) {
        return value == null || value.isBlank() ? "<unknown>" : value.trim();
    }

    private String errorMessage(Exception ex) {
        return MessagePublishFailureSanitizer.sanitize(ex);
    }

    private String truncate(String value) {
        return value.length() > 120 ? value.substring(0, 117) + "..." : value;
    }
}
