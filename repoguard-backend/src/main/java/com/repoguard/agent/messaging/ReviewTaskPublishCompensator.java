package com.repoguard.agent.messaging;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.repoguard.agent.config.RabbitReviewQueueProperties;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.entity.ReviewTimeline;
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
public class ReviewTaskPublishCompensator {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReviewTaskPublishCompensator.class);

    private final ReviewTaskMapper reviewTaskMapper;
    private final ReviewTimelineMapper reviewTimelineMapper;
    private final ReviewTaskPublisher reviewTaskPublisher;
    private final RabbitReviewQueueProperties properties;
    private final String instanceId;
    private final RepoGuardMetrics metrics;
    private final ReviewTaskStateMachine reviewTaskStateMachine;

    @Autowired
    public ReviewTaskPublishCompensator(
        ReviewTaskMapper reviewTaskMapper,
        ReviewTimelineMapper reviewTimelineMapper,
        ReviewTaskPublisher reviewTaskPublisher,
        RabbitReviewQueueProperties properties,
        RepoGuardMetrics metrics,
        ReviewTaskStateMachine reviewTaskStateMachine
    ) {
        this(
            reviewTaskMapper,
            reviewTimelineMapper,
            reviewTaskPublisher,
            properties,
            "repoguard-" + UUID.randomUUID(),
            metrics,
            reviewTaskStateMachine
        );
    }

    ReviewTaskPublishCompensator(
        ReviewTaskMapper reviewTaskMapper,
        ReviewTimelineMapper reviewTimelineMapper,
        ReviewTaskPublisher reviewTaskPublisher,
        RabbitReviewQueueProperties properties,
        String instanceId
    ) {
        this(reviewTaskMapper, reviewTimelineMapper, reviewTaskPublisher, properties, instanceId, null, null);
    }

    ReviewTaskPublishCompensator(
        ReviewTaskMapper reviewTaskMapper,
        ReviewTimelineMapper reviewTimelineMapper,
        ReviewTaskPublisher reviewTaskPublisher,
        RabbitReviewQueueProperties properties,
        String instanceId,
        RepoGuardMetrics metrics
    ) {
        this(reviewTaskMapper, reviewTimelineMapper, reviewTaskPublisher, properties, instanceId, metrics, null);
    }

    ReviewTaskPublishCompensator(
        ReviewTaskMapper reviewTaskMapper,
        ReviewTimelineMapper reviewTimelineMapper,
        ReviewTaskPublisher reviewTaskPublisher,
        RabbitReviewQueueProperties properties,
        String instanceId,
        RepoGuardMetrics metrics,
        ReviewTaskStateMachine reviewTaskStateMachine
    ) {
        this.reviewTaskMapper = reviewTaskMapper;
        this.reviewTimelineMapper = reviewTimelineMapper;
        this.reviewTaskPublisher = reviewTaskPublisher;
        this.properties = properties;
        this.instanceId = instanceId;
        this.metrics = metrics;
        this.reviewTaskStateMachine = reviewTaskStateMachine == null
            ? new ReviewTaskStateMachine()
            : reviewTaskStateMachine;
    }

    @Scheduled(fixedDelayString = "${app.rabbit.review.publish-compensation-interval-ms:60000}")
    public void compensatePublishFailures() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiredBefore = now.minusNanos(leaseMs() * 1_000_000);
        List<ReviewTask> tasks = reviewTaskMapper.selectList(
            new LambdaQueryWrapper<ReviewTask>()
                .and(wrapper -> wrapper
                    .and(failed -> failed
                        .eq(ReviewTask::getStatus, reviewTaskStateMachine.statusWhenPublishFailed())
                        .le(ReviewTask::getNextPublishRetryAt, now)
                        .lt(ReviewTask::getPublishAttempts, maxAttempts())
                    )
                    .or(staleQueued -> staleQueued
                        .eq(ReviewTask::getStatus, reviewTaskStateMachine.statusWhenQueued())
                        .isNotNull(ReviewTask::getPublishClaimedAt)
                        .le(ReviewTask::getPublishClaimedAt, expiredBefore)
                        .lt(ReviewTask::getPublishAttempts, maxAttempts())
                    )
                )
                .orderByAsc(ReviewTask::getPublishClaimedAt)
                .orderByAsc(ReviewTask::getNextPublishRetryAt)
                .last("limit " + batchSize())
        );
        for (ReviewTask task : tasks) {
            compensate(task);
        }
    }

    void compensate(ReviewTask task) {
        try (LogContext.Scope ignored = LogContext.withReviewTask(task)) {
            LocalDateTime claimedAt = LocalDateTime.now();
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
            int nextAttempt = safeAttempts(task) + 1;
            String recoverySource = reviewTaskStateMachine.isPublishFailed(task.getStatus())
                ? "publish_failed"
                : "stale_queued_claim";
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
                clearPublishClaim(task, claimedAt);
                appendTimeline(task.getId(), "Message publish recovered", LocalDateTime.now(), "CURRENT");
                if (metrics != null) {
                    metrics.rabbitPublishCompensationSucceeded();
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
                    appendTimeline(
                        task.getId(),
                        "Message publish retry failed: " + truncate(errorMessage),
                        LocalDateTime.now(),
                        "FAILED"
                    );
                    if (metrics != null) {
                        metrics.rabbitPublishCompensationFailed(errorMessage);
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
        LocalDateTime expiredBefore = claimedAt.minusNanos(leaseMs() * 1_000_000);
        int updated = reviewTaskMapper.update(
            new UpdateWrapper<ReviewTask>()
                .eq("id", task.getId())
                .and(wrapper -> wrapper
                    .and(failed -> failed
                        .eq("status", reviewTaskStateMachine.statusWhenPublishFailed())
                        .le("next_publish_retry_at", claimedAt)
                        .lt("publish_attempts", maxAttempts())
                        .and(claim -> claim
                            .isNull("publish_claimed_at")
                            .or()
                            .le("publish_claimed_at", expiredBefore)
                        )
                    )
                    .or(staleQueued -> staleQueued
                        .eq("status", reviewTaskStateMachine.statusWhenQueued())
                        .isNotNull("publish_claimed_at")
                        .le("publish_claimed_at", expiredBefore)
                        .lt("publish_attempts", maxAttempts())
                    )
                )
                .set("publish_claimed_at", claimedAt)
                .set("publish_claimed_by", instanceId)
        );
        if (updated > 0) {
            task.setPublishClaimedAt(claimedAt);
            task.setPublishClaimedBy(instanceId);
            return true;
        }
        return false;
    }

    private boolean markQueuedBeforePublish(ReviewTask task, LocalDateTime claimedAt, int nextAttempt) {
        int updated = reviewTaskMapper.update(
            new UpdateWrapper<ReviewTask>()
                .eq("id", task.getId())
                .in(
                    "status",
                    reviewTaskStateMachine.statusWhenPublishFailed(),
                    reviewTaskStateMachine.statusWhenQueued()
                )
                .eq("publish_claimed_at", claimedAt)
                .eq("publish_claimed_by", instanceId)
                .set("status", reviewTaskStateMachine.statusWhenQueued())
                .set("llm_status", "PENDING")
                .set("publish_attempts", nextAttempt)
                .set("next_publish_retry_at", null)
                .set("last_publish_error", null)
        );
        if (updated <= 0) {
            return false;
        }
        task.setStatus(reviewTaskStateMachine.statusWhenQueued());
        task.setLlmStatus("PENDING");
        task.setPublishAttempts(nextAttempt);
        task.setNextPublishRetryAt(null);
        task.setLastPublishError(null);
        return true;
    }

    private void clearPublishClaim(ReviewTask task, LocalDateTime claimedAt) {
        int updated = reviewTaskMapper.update(
            new UpdateWrapper<ReviewTask>()
                .eq("id", task.getId())
                .eq("status", reviewTaskStateMachine.statusWhenQueued())
                .eq("publish_claimed_at", claimedAt)
                .eq("publish_claimed_by", instanceId)
                .set("publish_claimed_at", null)
                .set("publish_claimed_by", null)
        );
        if (updated > 0) {
            task.setPublishClaimedAt(null);
            task.setPublishClaimedBy(null);
        }
    }

    private boolean markPublishFailed(ReviewTask task, LocalDateTime claimedAt, MessagePublishException ex) {
        LocalDateTime nextRetryAt = LocalDateTime.now().plusNanos(retryIntervalMs() * 1_000_000);
        String error = truncate(errorMessage(ex));
        int updated = reviewTaskMapper.update(
            new UpdateWrapper<ReviewTask>()
                .eq("id", task.getId())
                .eq("status", reviewTaskStateMachine.statusWhenQueued())
                .eq("publish_claimed_at", claimedAt)
                .eq("publish_claimed_by", instanceId)
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

    private void appendTimeline(Long taskId, String label, LocalDateTime eventTime, String status) {
        ReviewTimeline timeline = new ReviewTimeline();
        timeline.setTaskId(taskId);
        timeline.setLabel(truncate(label));
        timeline.setEventTime(eventTime);
        timeline.setStatus(status);
        timeline.setSortOrder(nextTimelineSortOrder(taskId));
        reviewTimelineMapper.insert(timeline);
    }

    private int nextTimelineSortOrder(Long taskId) {
        ReviewTimeline latest = reviewTimelineMapper.selectOne(
            new LambdaQueryWrapper<ReviewTimeline>()
                .eq(ReviewTimeline::getTaskId, taskId)
                .orderByDesc(ReviewTimeline::getSortOrder)
                .last("limit 1")
        );
        return latest == null || latest.getSortOrder() == null ? 1 : latest.getSortOrder() + 1;
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
        return Math.max(1, properties.getPublishCompensationMaxAttempts());
    }

    private int batchSize() {
        return Math.max(1, properties.getPublishCompensationBatchSize());
    }

    private long retryIntervalMs() {
        return Math.max(1000, properties.getPublishCompensationIntervalMs());
    }

    private long leaseMs() {
        return Math.max(1000, properties.getPublishCompensationLeaseMs());
    }

    private String errorMessage(Exception ex) {
        return ex.getMessage() == null || ex.getMessage().isBlank()
            ? ex.getClass().getSimpleName()
            : ex.getMessage().replaceAll("\\s+", " ").trim();
    }

    private String truncate(String value) {
        return value.length() > 120 ? value.substring(0, 117) + "..." : value;
    }
}
