package com.repoguard.agent.messaging;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.review.LlmStatus;
import com.repoguard.agent.review.ReviewTaskStateMachine;
import com.repoguard.agent.timeline.ReviewTimelineAppender;
import com.repoguard.agent.timeline.ReviewTimelineStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class ReviewTaskPublishOutboxStore {

    private final ReviewTaskMapper reviewTaskMapper;
    private final ReviewTaskStateMachine reviewTaskStateMachine;
    private final ReviewTimelineAppender reviewTimelineAppender;

    public ReviewTaskPublishOutboxStore(
        ReviewTaskMapper reviewTaskMapper,
        ReviewTimelineAppender reviewTimelineAppender,
        ReviewTaskStateMachine reviewTaskStateMachine
    ) {
        this.reviewTaskMapper = Objects.requireNonNull(reviewTaskMapper, "reviewTaskMapper");
        this.reviewTaskStateMachine = Objects.requireNonNull(reviewTaskStateMachine, "reviewTaskStateMachine");
        this.reviewTimelineAppender = Objects.requireNonNull(reviewTimelineAppender, "reviewTimelineAppender");
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
                        .and(RabbitPublishClaimConditions.staleQueuedLambda(
                            ReviewTask::getPublishClaimedAt,
                            ReviewTask::getCreatedAt,
                            expiredBefore
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
        RabbitPublishClaim claim
    ) {
        Objects.requireNonNull(claim, "claim");
        int updated = reviewTaskMapper.update(
            new UpdateWrapper<ReviewTask>()
                .eq("id", task.getId())
                .and(wrapper -> wrapper
                    .and(failed -> failed
                        .in("status", reviewTaskStateMachine.publishRecoveryCandidateStatuses())
                        .le("next_publish_retry_at", claim.claimedAt())
                        .lt("publish_attempts", claim.maxAttempts())
                        .and(RabbitPublishClaimConditions.availableColumn(
                            "publish_claimed_at",
                            claim.expiredBefore()
                        ))
                    )
                    .or(staleQueued -> staleQueued
                        .eq("status", reviewTaskStateMachine.statusWhenQueued())
                        .and(RabbitPublishClaimConditions.staleQueuedColumn(
                            "publish_claimed_at",
                            "created_at",
                            claim.expiredBefore()
                        ))
                        .lt("publish_attempts", claim.maxAttempts())
                    )
                )
                .set("publish_claimed_at", claim.claimedAt())
                .set("publish_claimed_by", claim.instanceId())
        );
        if (updated <= 0) {
            return false;
        }
        task.setPublishClaimedAt(claim.claimedAt());
        task.setPublishClaimedBy(claim.instanceId());
        return true;
    }

    public boolean markQueuedForPublish(
        ReviewTask task,
        RabbitPublishClaim claim,
        int nextAttempt
    ) {
        Objects.requireNonNull(claim, "claim");
        int updated = reviewTaskMapper.update(
            new UpdateWrapper<ReviewTask>()
                .eq("id", task.getId())
                .in("status", reviewTaskStateMachine.publishQueueCandidateStatuses())
                .eq("publish_claimed_at", claim.claimedAt())
                .eq("publish_claimed_by", claim.instanceId())
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

    public boolean clearPublishClaim(ReviewTask task, RabbitPublishClaim claim) {
        Objects.requireNonNull(claim, "claim");
        int updated = reviewTaskMapper.update(
            new UpdateWrapper<ReviewTask>()
                .eq("id", task.getId())
                .eq("status", reviewTaskStateMachine.statusWhenQueued())
                .eq("publish_claimed_at", claim.claimedAt())
                .eq("publish_claimed_by", claim.instanceId())
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
        RabbitPublishClaim claim,
        LocalDateTime nextRetryAt,
        String error
    ) {
        Objects.requireNonNull(claim, "claim");
        int updated = reviewTaskMapper.update(
            new UpdateWrapper<ReviewTask>()
                .eq("id", task.getId())
                .eq("status", reviewTaskStateMachine.statusWhenQueued())
                .eq("publish_claimed_at", claim.claimedAt())
                .eq("publish_claimed_by", claim.instanceId())
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

    public boolean markDirectPublishFailed(
        ReviewTask task,
        MessagePublishException ex,
        LocalDateTime failedAt,
        ReviewTaskDirectPublishFailurePolicy policy
    ) {
        Objects.requireNonNull(policy, "policy");
        String error = truncate(errorMessage(ex));
        int nextAttempt = safeAttempts(task) + 1;
        LocalDateTime nextRetryAt = failedAt.plusNanos(policy.normalizedRetryDelayMs() * 1_000_000);
        UpdateWrapper<ReviewTask> update = new UpdateWrapper<ReviewTask>()
            .eq("id", task.getId())
            .eq("status", reviewTaskStateMachine.statusWhenQueued())
            .eq("publish_attempts", safeAttempts(task))
            .isNull("publish_claimed_at")
            .set("status", reviewTaskStateMachine.statusWhenPublishFailed())
            .set("llm_status", LlmStatus.PENDING.code())
            .set("publish_attempts", nextAttempt)
            .set("next_publish_retry_at", nextRetryAt)
            .set("last_publish_error", error)
            .set("publish_claimed_at", null)
            .set("publish_claimed_by", null);
        if (policy.clearLlmQuality()) {
            clearLlmQuality(update);
        }
        if (reviewTaskMapper.update(update) <= 0) {
            return false;
        }
        task.setStatus(reviewTaskStateMachine.statusWhenPublishFailed());
        task.setLlmStatus(LlmStatus.PENDING.code());
        if (policy.clearLlmQuality()) {
            clearLlmQuality(task);
        }
        task.setPublishAttempts(nextAttempt);
        task.setNextPublishRetryAt(nextRetryAt);
        task.setLastPublishError(error);
        task.setPublishClaimedAt(null);
        task.setPublishClaimedBy(null);
        if (policy.closeCurrentTimeline()) {
            markCurrentTimelinesDone(task.getId());
        }
        appendTimeline(task.getId(), policy.timelinePrefix() + error, failedAt, ReviewTimelineStatus.FAILED);
        return true;
    }

    public boolean markQueuedForRecoveryPublish(ReviewTask task, int nextAttempt) {
        int updated = reviewTaskMapper.update(
            new UpdateWrapper<ReviewTask>()
                .eq("id", task.getId())
                .eq("status", reviewTaskStateMachine.statusWhenRequeuePending())
                .set("status", reviewTaskStateMachine.statusWhenQueued())
                .set("llm_status", LlmStatus.PENDING.code())
                .set("publish_attempts", nextAttempt)
                .set("next_publish_retry_at", null)
                .set("last_publish_error", null)
                .set("publish_claimed_at", null)
                .set("publish_claimed_by", null)
        );
        if (updated <= 0) {
            return false;
        }
        task.setStatus(reviewTaskStateMachine.statusWhenQueued());
        task.setLlmStatus(LlmStatus.PENDING.code());
        task.setPublishAttempts(nextAttempt);
        task.setNextPublishRetryAt(null);
        task.setLastPublishError(null);
        task.setPublishClaimedAt(null);
        task.setPublishClaimedBy(null);
        return true;
    }

    public boolean markRecoveryPublishFailed(
        ReviewTask task,
        LocalDateTime nextRetryAt,
        String error
    ) {
        int updated = reviewTaskMapper.update(
            new UpdateWrapper<ReviewTask>()
                .eq("id", task.getId())
                .eq("status", reviewTaskStateMachine.statusWhenQueued())
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

    public void markCurrentTimelinesDone(Long taskId) {
        reviewTimelineAppender.completeCurrentTimelines(taskId);
    }

    public void appendTimeline(Long taskId, String label, LocalDateTime eventTime, ReviewTimelineStatus status) {
        reviewTimelineAppender.append(taskId, truncate(label), eventTime, status.code());
    }

    private void clearLlmQuality(ReviewTask task) {
        task.setLlmProvider(null);
        task.setLlmModel(null);
        task.setLlmDurationMs(null);
        task.setLlmParseStatus(null);
        task.setLlmFallbackReason(null);
        task.setLlmPromptSummary(null);
        task.setLlmPromptTokens(null);
        task.setLlmCompletionTokens(null);
        task.setLlmTotalTokens(null);
        task.setLlmEstimatedCost(null);
    }

    private void clearLlmQuality(UpdateWrapper<ReviewTask> update) {
        update
            .set("llm_provider", null)
            .set("llm_model", null)
            .set("llm_duration_ms", null)
            .set("llm_parse_status", null)
            .set("llm_fallback_reason", null)
            .set("llm_prompt_summary", null)
            .set("llm_prompt_tokens", null)
            .set("llm_completion_tokens", null)
            .set("llm_total_tokens", null)
            .set("llm_estimated_cost", null);
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
