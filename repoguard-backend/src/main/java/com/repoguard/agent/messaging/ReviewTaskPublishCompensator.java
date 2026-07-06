package com.repoguard.agent.messaging;

import com.repoguard.agent.config.RabbitReviewQueueProperties;
import com.repoguard.agent.config.WorkerRuntimeEnabled;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.mapper.ReviewTimelineMapper;
import com.repoguard.agent.observability.LogContext;
import com.repoguard.agent.observability.RepoGuardMetrics;
import com.repoguard.agent.review.ReviewTaskStateMachine;
import java.time.LocalDateTime;
import java.util.List;
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
    private final RabbitReviewQueueProperties properties;
    private final String instanceId;
    private final RepoGuardMetrics metrics;
    private final ReviewTaskStateMachine reviewTaskStateMachine;
    private final RabbitPublishFailureClassifier failureClassifier;
    private final RabbitPublishCompensationPolicy compensationPolicy;

    @Autowired
    public ReviewTaskPublishCompensator(
        ReviewTaskMapper reviewTaskMapper,
        ReviewTimelineMapper reviewTimelineMapper,
        ReviewTaskPublisher reviewTaskPublisher,
        RabbitReviewQueueProperties properties,
        RepoGuardMetrics metrics,
        ReviewTaskPublishOutboxStore outboxStore,
        ReviewTaskStateMachine reviewTaskStateMachine,
        RabbitPublishCompensationPolicy compensationPolicy
    ) {
        this(
            reviewTaskMapper,
            reviewTimelineMapper,
            reviewTaskPublisher,
            properties,
            "repoguard-" + UUID.randomUUID(),
            metrics,
            outboxStore,
            reviewTaskStateMachine,
            compensationPolicy
        );
    }

    ReviewTaskPublishCompensator(
        ReviewTaskMapper reviewTaskMapper,
        ReviewTimelineMapper reviewTimelineMapper,
        ReviewTaskPublisher reviewTaskPublisher,
        RabbitReviewQueueProperties properties,
        String instanceId
    ) {
        this(reviewTaskMapper, reviewTimelineMapper, reviewTaskPublisher, properties, instanceId, null, null, null, null);
    }

    ReviewTaskPublishCompensator(
        ReviewTaskMapper reviewTaskMapper,
        ReviewTimelineMapper reviewTimelineMapper,
        ReviewTaskPublisher reviewTaskPublisher,
        RabbitReviewQueueProperties properties,
        String instanceId,
        RepoGuardMetrics metrics
    ) {
        this(reviewTaskMapper, reviewTimelineMapper, reviewTaskPublisher, properties, instanceId, metrics, null, null, null);
    }

    ReviewTaskPublishCompensator(
        ReviewTaskMapper reviewTaskMapper,
        ReviewTimelineMapper reviewTimelineMapper,
        ReviewTaskPublisher reviewTaskPublisher,
        RabbitReviewQueueProperties properties,
        String instanceId,
        RepoGuardMetrics metrics,
        ReviewTaskPublishOutboxStore outboxStore,
        ReviewTaskStateMachine reviewTaskStateMachine,
        RabbitPublishCompensationPolicy compensationPolicy
    ) {
        this.reviewTaskPublisher = reviewTaskPublisher;
        this.outboxStore = outboxStore == null
            ? new ReviewTaskPublishOutboxStore(reviewTaskMapper, reviewTimelineMapper, reviewTaskStateMachine)
            : outboxStore;
        this.properties = properties;
        this.instanceId = instanceId;
        this.metrics = metrics;
        this.reviewTaskStateMachine = reviewTaskStateMachine == null
            ? new ReviewTaskStateMachine()
            : reviewTaskStateMachine;
        this.failureClassifier = new RabbitPublishFailureClassifier();
        this.compensationPolicy = compensationPolicy == null
            ? new RabbitPublishCompensationPolicy()
            : compensationPolicy;
    }

    @Scheduled(fixedDelayString = "${app.rabbit.review.publish-compensation-interval-ms:60000}")
    public void compensatePublishFailures() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiredBefore = compensationPolicy.expiredBefore(now, properties.getPublishCompensationLeaseMs());
        List<ReviewTask> tasks = outboxStore.loadDuePublishEvents(now, expiredBefore, maxAttempts(), batchSize());
        for (ReviewTask task : tasks) {
            compensate(task);
        }
    }

    void compensate(ReviewTask task) {
        try (LogContext.Scope ignored = LogContext.withReviewTask(task)) {
            LocalDateTime claimedAt = LocalDateTime.now();
            String recoverySource = recoverySource(task);
            if (!claimTask(task, claimedAt)) {
                LOGGER.info(
                    "Review task publish compensation skipped taskId={} repository={} prNumber={} operation=review_publish_compensation result=claim_failed status={} attempts={} maxAttempts={}",
                    task.getId(),
                    repositorySlug(task),
                    task.getPrNumber(),
                    task.getStatus(),
                    safeAttempts(task),
                    maxAttempts()
                );
                return;
            }
            int nextAttempt = compensationPolicy.nextAttempt(task.getPublishAttempts());
            LOGGER.info(
                "Review task publish compensation claimed taskId={} repository={} prNumber={} operation=review_publish_compensation recoverySource={} currentStatus={} nextAttempt={} maxAttempts={} claimedAt={}",
                task.getId(),
                repositorySlug(task),
                task.getPrNumber(),
                recoverySource,
                task.getStatus(),
                nextAttempt,
                maxAttempts(),
                claimedAt
            );
            if (!markQueuedBeforePublish(task, claimedAt, nextAttempt)) {
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
                outboxStore.clearPublishClaim(task, claimedAt, instanceId);
                outboxStore.appendTimeline(task.getId(), "Message publish recovered", LocalDateTime.now(), "CURRENT");
                if (metrics != null) {
                    metrics.rabbitPublishCompensationSucceeded("publish");
                }
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
                if (markPublishFailed(task, claimedAt, ex)) {
                    outboxStore.appendTimeline(
                        task.getId(),
                        "Message publish retry failed: " + truncate(errorMessage),
                        LocalDateTime.now(),
                        "FAILED"
                    );
                    if (metrics != null) {
                        metrics.rabbitPublishCompensationFailed(failureClassifier.classify(ex));
                    }
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

    private boolean claimTask(ReviewTask task, LocalDateTime claimedAt) {
        LocalDateTime expiredBefore = compensationPolicy.expiredBefore(claimedAt, properties.getPublishCompensationLeaseMs());
        return outboxStore.claimForPublish(task, claimedAt, instanceId, expiredBefore, maxAttempts());
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

    private boolean markQueuedBeforePublish(ReviewTask task, LocalDateTime claimedAt, int nextAttempt) {
        return outboxStore.markQueuedForPublish(task, claimedAt, instanceId, nextAttempt);
    }

    private boolean markPublishFailed(ReviewTask task, LocalDateTime claimedAt, MessagePublishException ex) {
        LocalDateTime nextRetryAt = compensationPolicy.nextRetryAt(
            LocalDateTime.now(),
            properties
        );
        String error = truncate(errorMessage(ex));
        return outboxStore.markClaimedPublishFailed(task, claimedAt, instanceId, nextRetryAt, error);
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

    private int maxAttempts() {
        return compensationPolicy.maxAttempts(properties);
    }

    private int batchSize() {
        return compensationPolicy.batchSize(properties);
    }

    private String errorMessage(Exception ex) {
        return MessagePublishFailureSanitizer.sanitize(ex);
    }

    private String truncate(String value) {
        return value.length() > 120 ? value.substring(0, 117) + "..." : value;
    }
}
