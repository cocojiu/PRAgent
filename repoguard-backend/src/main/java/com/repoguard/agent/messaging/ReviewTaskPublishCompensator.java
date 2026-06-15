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
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ReviewTaskPublishCompensator {

    private static final String STATUS_PUBLISH_FAILED = "PUBLISH_FAILED";
    private static final String STATUS_QUEUED = "QUEUED";

    private final ReviewTaskMapper reviewTaskMapper;
    private final ReviewTimelineMapper reviewTimelineMapper;
    private final ReviewTaskPublisher reviewTaskPublisher;
    private final RabbitReviewQueueProperties properties;
    private final String instanceId;
    private final RepoGuardMetrics metrics;

    @Autowired
    public ReviewTaskPublishCompensator(
        ReviewTaskMapper reviewTaskMapper,
        ReviewTimelineMapper reviewTimelineMapper,
        ReviewTaskPublisher reviewTaskPublisher,
        RabbitReviewQueueProperties properties,
        RepoGuardMetrics metrics
    ) {
        this(
            reviewTaskMapper,
            reviewTimelineMapper,
            reviewTaskPublisher,
            properties,
            "repoguard-" + UUID.randomUUID(),
            metrics
        );
    }

    ReviewTaskPublishCompensator(
        ReviewTaskMapper reviewTaskMapper,
        ReviewTimelineMapper reviewTimelineMapper,
        ReviewTaskPublisher reviewTaskPublisher,
        RabbitReviewQueueProperties properties,
        String instanceId
    ) {
        this(reviewTaskMapper, reviewTimelineMapper, reviewTaskPublisher, properties, instanceId, null);
    }

    ReviewTaskPublishCompensator(
        ReviewTaskMapper reviewTaskMapper,
        ReviewTimelineMapper reviewTimelineMapper,
        ReviewTaskPublisher reviewTaskPublisher,
        RabbitReviewQueueProperties properties,
        String instanceId,
        RepoGuardMetrics metrics
    ) {
        this.reviewTaskMapper = reviewTaskMapper;
        this.reviewTimelineMapper = reviewTimelineMapper;
        this.reviewTaskPublisher = reviewTaskPublisher;
        this.properties = properties;
        this.instanceId = instanceId;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${app.rabbit.review.publish-compensation-interval-ms:60000}")
    public void compensatePublishFailures() {
        List<ReviewTask> tasks = reviewTaskMapper.selectList(
            new LambdaQueryWrapper<ReviewTask>()
                .eq(ReviewTask::getStatus, STATUS_PUBLISH_FAILED)
                .le(ReviewTask::getNextPublishRetryAt, LocalDateTime.now())
                .lt(ReviewTask::getPublishAttempts, maxAttempts())
                .orderByAsc(ReviewTask::getNextPublishRetryAt)
                .last("limit " + batchSize())
        );
        for (ReviewTask task : tasks) {
            compensate(task);
        }
    }

    void compensate(ReviewTask task) {
        LocalDateTime claimedAt = LocalDateTime.now();
        if (!claimTask(task, claimedAt)) {
            return;
        }
        int nextAttempt = safeAttempts(task) + 1;
        try {
            reviewTaskPublisher.publish(toMessage(task, LocalDateTime.now()));
            task.setStatus(STATUS_QUEUED);
            task.setLlmStatus("PENDING");
            task.setPublishAttempts(nextAttempt);
            task.setNextPublishRetryAt(null);
            task.setLastPublishError(null);
            task.setPublishClaimedAt(null);
            task.setPublishClaimedBy(null);
            reviewTaskMapper.updateById(task);
            appendTimeline(task.getId(), "Message publish recovered", LocalDateTime.now(), "CURRENT");
            if (metrics != null) {
                metrics.rabbitPublishCompensationSucceeded();
            }
        } catch (MessagePublishException ex) {
            task.setPublishAttempts(nextAttempt);
            task.setNextPublishRetryAt(LocalDateTime.now().plusNanos(retryIntervalMs() * 1_000_000));
            task.setLastPublishError(truncate(errorMessage(ex)));
            task.setPublishClaimedAt(null);
            task.setPublishClaimedBy(null);
            reviewTaskMapper.updateById(task);
            appendTimeline(task.getId(), "Message publish retry failed: " + truncate(errorMessage(ex)), LocalDateTime.now(), "FAILED");
            if (metrics != null) {
                metrics.rabbitPublishCompensationFailed(errorMessage(ex));
            }
        }
    }

    private boolean claimTask(ReviewTask task, LocalDateTime claimedAt) {
        LocalDateTime expiredBefore = claimedAt.minusNanos(leaseMs() * 1_000_000);
        int updated = reviewTaskMapper.update(
            new UpdateWrapper<ReviewTask>()
                .eq("id", task.getId())
                .eq("status", STATUS_PUBLISH_FAILED)
                .le("next_publish_retry_at", claimedAt)
                .lt("publish_attempts", maxAttempts())
                .and(wrapper -> wrapper
                    .isNull("publish_claimed_at")
                    .or()
                    .le("publish_claimed_at", expiredBefore)
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
