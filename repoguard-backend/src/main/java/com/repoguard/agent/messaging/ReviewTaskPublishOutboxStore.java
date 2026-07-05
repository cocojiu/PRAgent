package com.repoguard.agent.messaging;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.entity.ReviewTimeline;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.mapper.ReviewTimelineMapper;
import com.repoguard.agent.review.LlmStatus;
import com.repoguard.agent.review.ReviewTaskStateMachine;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ReviewTaskPublishOutboxStore {

    private final ReviewTaskMapper reviewTaskMapper;
    private final ReviewTimelineMapper reviewTimelineMapper;
    private final ReviewTaskStateMachine reviewTaskStateMachine;

    public ReviewTaskPublishOutboxStore(
        ReviewTaskMapper reviewTaskMapper,
        ReviewTimelineMapper reviewTimelineMapper,
        ReviewTaskStateMachine reviewTaskStateMachine
    ) {
        this.reviewTaskMapper = reviewTaskMapper;
        this.reviewTimelineMapper = reviewTimelineMapper;
        this.reviewTaskStateMachine = reviewTaskStateMachine == null
            ? new ReviewTaskStateMachine()
            : reviewTaskStateMachine;
    }

    public List<ReviewTask> loadDuePublishEvents(
        LocalDateTime now,
        LocalDateTime expiredBefore,
        int maxAttempts,
        int batchSize
    ) {
        return reviewTaskMapper.selectList(
            new LambdaQueryWrapper<ReviewTask>()
                .and(wrapper -> wrapper
                    .and(failed -> failed
                        .in(ReviewTask::getStatus, reviewTaskStateMachine.publishRecoveryCandidateStatuses())
                        .le(ReviewTask::getNextPublishRetryAt, now)
                        .lt(ReviewTask::getPublishAttempts, maxAttempts)
                    )
                    .or(staleQueued -> staleQueued
                        .eq(ReviewTask::getStatus, reviewTaskStateMachine.statusWhenQueued())
                        .and(queued -> queued
                            .and(claimed -> claimed
                                .isNotNull(ReviewTask::getPublishClaimedAt)
                                .le(ReviewTask::getPublishClaimedAt, expiredBefore)
                            )
                            .or(unclaimed -> unclaimed
                                .isNull(ReviewTask::getPublishClaimedAt)
                                .le(ReviewTask::getCreatedAt, expiredBefore)
                            )
                        )
                        .lt(ReviewTask::getPublishAttempts, maxAttempts)
                    )
                )
                .orderByAsc(ReviewTask::getPublishClaimedAt)
                .orderByAsc(ReviewTask::getNextPublishRetryAt)
                .last("limit " + batchSize)
        );
    }

    public boolean claimForPublish(
        ReviewTask task,
        LocalDateTime claimedAt,
        String instanceId,
        LocalDateTime expiredBefore,
        int maxAttempts
    ) {
        int updated = reviewTaskMapper.update(
            new UpdateWrapper<ReviewTask>()
                .eq("id", task.getId())
                .and(wrapper -> wrapper
                    .and(failed -> failed
                        .in("status", reviewTaskStateMachine.publishRecoveryCandidateStatuses())
                        .le("next_publish_retry_at", claimedAt)
                        .lt("publish_attempts", maxAttempts)
                        .and(claim -> claim
                            .isNull("publish_claimed_at")
                            .or()
                            .le("publish_claimed_at", expiredBefore)
                        )
                    )
                    .or(staleQueued -> staleQueued
                        .eq("status", reviewTaskStateMachine.statusWhenQueued())
                        .and(queued -> queued
                            .and(claimed -> claimed
                                .isNotNull("publish_claimed_at")
                                .le("publish_claimed_at", expiredBefore)
                            )
                            .or(unclaimed -> unclaimed
                                .isNull("publish_claimed_at")
                                .le("created_at", expiredBefore)
                            )
                        )
                        .lt("publish_attempts", maxAttempts)
                    )
                )
                .set("publish_claimed_at", claimedAt)
                .set("publish_claimed_by", instanceId)
        );
        if (updated <= 0) {
            return false;
        }
        task.setPublishClaimedAt(claimedAt);
        task.setPublishClaimedBy(instanceId);
        return true;
    }

    public boolean markQueuedForPublish(
        ReviewTask task,
        LocalDateTime claimedAt,
        String instanceId,
        int nextAttempt
    ) {
        int updated = reviewTaskMapper.update(
            new UpdateWrapper<ReviewTask>()
                .eq("id", task.getId())
                .in("status", reviewTaskStateMachine.publishQueueCandidateStatuses())
                .eq("publish_claimed_at", claimedAt)
                .eq("publish_claimed_by", instanceId)
                .set("status", reviewTaskStateMachine.statusWhenQueued())
                .set("llm_status", LlmStatus.PENDING.code())
                .set("publish_attempts", nextAttempt)
                .set("next_publish_retry_at", null)
                .set("last_publish_error", null)
        );
        if (updated <= 0) {
            return false;
        }
        task.setStatus(reviewTaskStateMachine.statusWhenQueued());
        task.setLlmStatus(LlmStatus.PENDING.code());
        task.setPublishAttempts(nextAttempt);
        task.setNextPublishRetryAt(null);
        task.setLastPublishError(null);
        return true;
    }

    public boolean clearPublishClaim(ReviewTask task, LocalDateTime claimedAt, String instanceId) {
        int updated = reviewTaskMapper.update(
            new UpdateWrapper<ReviewTask>()
                .eq("id", task.getId())
                .eq("status", reviewTaskStateMachine.statusWhenQueued())
                .eq("publish_claimed_at", claimedAt)
                .eq("publish_claimed_by", instanceId)
                .set("publish_claimed_at", null)
                .set("publish_claimed_by", null)
        );
        if (updated <= 0) {
            return false;
        }
        task.setPublishClaimedAt(null);
        task.setPublishClaimedBy(null);
        return true;
    }

    public boolean markClaimedPublishFailed(
        ReviewTask task,
        LocalDateTime claimedAt,
        String instanceId,
        LocalDateTime nextRetryAt,
        String error
    ) {
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

    public void markDirectPublishFailed(
        ReviewTask task,
        MessagePublishException ex,
        LocalDateTime failedAt,
        long retryDelayMs,
        String timelinePrefix,
        boolean clearLlmQuality,
        boolean closeCurrentTimeline
    ) {
        String error = truncate(errorMessage(ex));
        task.setStatus(reviewTaskStateMachine.statusWhenPublishFailed());
        task.setLlmStatus(LlmStatus.PENDING.code());
        if (clearLlmQuality) {
            clearLlmQuality(task);
        }
        task.setPublishAttempts(safeAttempts(task) + 1);
        task.setNextPublishRetryAt(failedAt.plusNanos(Math.max(1000, retryDelayMs) * 1_000_000));
        task.setLastPublishError(error);
        task.setPublishClaimedAt(null);
        task.setPublishClaimedBy(null);
        reviewTaskMapper.updateById(task);
        if (closeCurrentTimeline) {
            markCurrentTimelinesDone(task.getId());
        }
        appendTimeline(task.getId(), timelinePrefix + error, failedAt, "FAILED");
    }

    public void markCurrentTimelinesDone(Long taskId) {
        reviewTimelineMapper.update(
            new UpdateWrapper<ReviewTimeline>()
                .eq("task_id", taskId)
                .eq("status", "CURRENT")
                .set("status", "DONE")
        );
    }

    public void appendTimeline(Long taskId, String label, LocalDateTime eventTime, String status) {
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

    private void clearLlmQuality(ReviewTask task) {
        task.setLlmProvider(null);
        task.setLlmModel(null);
        task.setLlmDurationMs(null);
        task.setLlmParseStatus(null);
        task.setLlmFallbackReason(null);
        task.setLlmPromptSummary(null);
    }

    private int safeAttempts(ReviewTask task) {
        return task.getPublishAttempts() == null ? 0 : task.getPublishAttempts();
    }

    private String errorMessage(Exception ex) {
        return MessagePublishFailureSanitizer.sanitize(ex);
    }

    public String truncate(String value) {
        return value.length() > 120 ? value.substring(0, 117) + "..." : value;
    }
}
