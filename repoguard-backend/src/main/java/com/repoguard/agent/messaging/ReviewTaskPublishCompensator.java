package com.repoguard.agent.messaging;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.repoguard.agent.config.RabbitReviewQueueProperties;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.entity.ReviewTimeline;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.mapper.ReviewTimelineMapper;
import java.time.LocalDateTime;
import java.util.List;
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

    public ReviewTaskPublishCompensator(
        ReviewTaskMapper reviewTaskMapper,
        ReviewTimelineMapper reviewTimelineMapper,
        ReviewTaskPublisher reviewTaskPublisher,
        RabbitReviewQueueProperties properties
    ) {
        this.reviewTaskMapper = reviewTaskMapper;
        this.reviewTimelineMapper = reviewTimelineMapper;
        this.reviewTaskPublisher = reviewTaskPublisher;
        this.properties = properties;
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
        int nextAttempt = safeAttempts(task) + 1;
        try {
            reviewTaskPublisher.publish(toMessage(task, LocalDateTime.now()));
            task.setStatus(STATUS_QUEUED);
            task.setLlmStatus("PENDING");
            task.setPublishAttempts(nextAttempt);
            task.setNextPublishRetryAt(null);
            task.setLastPublishError(null);
            reviewTaskMapper.updateById(task);
            appendTimeline(task.getId(), "Message publish recovered", LocalDateTime.now(), "CURRENT");
        } catch (MessagePublishException ex) {
            task.setPublishAttempts(nextAttempt);
            task.setNextPublishRetryAt(LocalDateTime.now().plusNanos(retryIntervalMs() * 1_000_000));
            task.setLastPublishError(truncate(errorMessage(ex)));
            reviewTaskMapper.updateById(task);
            appendTimeline(task.getId(), "Message publish retry failed: " + truncate(errorMessage(ex)), LocalDateTime.now(), "FAILED");
        }
    }

    private ReviewTaskMessage toMessage(ReviewTask task, LocalDateTime queuedAt) {
        return new ReviewTaskMessage(
            task.getId(),
            task.getOrganization(),
            task.getRepository(),
            task.getPrNumber(),
            task.getCommitSha(),
            queuedAt
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

    private String errorMessage(Exception ex) {
        return ex.getMessage() == null || ex.getMessage().isBlank()
            ? ex.getClass().getSimpleName()
            : ex.getMessage().replaceAll("\\s+", " ").trim();
    }

    private String truncate(String value) {
        return value.length() > 120 ? value.substring(0, 117) + "..." : value;
    }
}
