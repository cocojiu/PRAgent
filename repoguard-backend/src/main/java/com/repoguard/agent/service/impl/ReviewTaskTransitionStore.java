package com.repoguard.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.review.HumanReviewStatus;
import com.repoguard.agent.review.LlmStatus;
import com.repoguard.agent.review.ReviewTaskStateMachine;
import java.time.LocalDateTime;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ReviewTaskTransitionStore {

    public static final String STATE_CHANGED_MESSAGE = "状态已变化，请刷新";

    private final ReviewTaskMapper reviewTaskMapper;
    private final ReviewTaskStateMachine reviewTaskStateMachine;

    public ReviewTaskTransitionStore(
        ReviewTaskMapper reviewTaskMapper,
        ReviewTaskStateMachine reviewTaskStateMachine
    ) {
        this.reviewTaskMapper = Objects.requireNonNull(reviewTaskMapper, "reviewTaskMapper");
        this.reviewTaskStateMachine = Objects.requireNonNull(
            reviewTaskStateMachine,
            "reviewTaskStateMachine"
        );
    }

    public ReviewTask findById(Long taskId) {
        return reviewTaskMapper.selectById(taskId);
    }

    public void retryFailedTask(ReviewTask task, int retryCount) {
        retryReviewTask(task, retryCount, task.getCommitSha());
    }

    public void retryReviewTask(ReviewTask task, int retryCount, String replacementCommitSha) {
        Objects.requireNonNull(task, "task");
        if (!StringUtils.hasText(replacementCommitSha)) {
            throw new IllegalArgumentException("replacementCommitSha must not be blank");
        }
        String observedStatus = task.getStatus();
        String observedCommitSha = task.getCommitSha();
        UpdateWrapper<ReviewTask> update = resetForQueuedExecution(
            new UpdateWrapper<ReviewTask>()
                .eq("id", task.getId())
                .eq("status", observedStatus)
                .set("mq_retries", retryCount)
                .set("commit_sha", replacementCommitSha.trim())
        );
        if (StringUtils.hasText(observedCommitSha)) {
            update.eq("commit_sha", observedCommitSha);
        } else {
            update.isNull("commit_sha");
        }
        ensureTransitioned(reviewTaskMapper.update(update));
        applyQueuedExecutionState(task);
        task.setMqRetries(retryCount);
        task.setCommitSha(replacementCommitSha.trim());
    }

    public void completeHumanReview(
        ReviewTask task,
        String taskStatus,
        String humanReviewStatus,
        String note,
        String operator,
        LocalDateTime reviewedAt
    ) {
        Objects.requireNonNull(task, "task");
        int updated = reviewTaskMapper.update(
            new UpdateWrapper<ReviewTask>()
                .eq("id", task.getId())
                .eq("status", reviewTaskStateMachine.statusAfterReviewCompleted(true))
                .eq("human_review_required", true)
                .eq("human_review_status", HumanReviewStatus.PENDING.code())
                .set("status", taskStatus)
                .set("human_review_status", humanReviewStatus)
                .set("human_review_note", note)
                .set("human_review_by", operator)
                .set("human_reviewed_at", reviewedAt)
        );
        ensureTransitioned(updated);
        task.setStatus(taskStatus);
        task.setHumanReviewStatus(humanReviewStatus);
        task.setHumanReviewNote(note);
        task.setHumanReviewBy(operator);
        task.setHumanReviewedAt(reviewedAt);
    }

    public void requeueForPublish(ReviewTask task) {
        Objects.requireNonNull(task, "task");
        UpdateWrapper<ReviewTask> update = resetForQueuedExecution(
            new UpdateWrapper<ReviewTask>()
                .eq("id", task.getId())
                .eq("status", task.getStatus())
                .isNull("publish_claimed_at")
        );
        ensureTransitioned(reviewTaskMapper.update(update));
        applyQueuedExecutionState(task);
    }

    private UpdateWrapper<ReviewTask> resetForQueuedExecution(UpdateWrapper<ReviewTask> update) {
        return update
            .set("status", reviewTaskStateMachine.statusWhenQueued())
            .set("risk_level", "INFO")
            .set("publish_attempts", 0)
            .set("next_publish_retry_at", null)
            .set("last_publish_error", null)
            .set("publish_claimed_at", null)
            .set("publish_claimed_by", null)
            .set("review_claimed_at", null)
            .set("review_claimed_by", null)
            .set("llm_status", LlmStatus.PENDING.code())
            .set("llm_provider", null)
            .set("llm_model", null)
            .set("llm_duration_ms", null)
            .set("llm_parse_status", null)
            .set("llm_fallback_reason", null)
            .set("llm_prompt_summary", null)
            .set("llm_prompt_tokens", null)
            .set("llm_completion_tokens", null)
            .set("llm_total_tokens", null)
            .set("llm_estimated_cost", null)
            .set("human_review_required", false)
            .set("human_review_status", HumanReviewStatus.NOT_REQUIRED.code())
            .set("human_review_note", null)
            .set("human_review_by", null)
            .set("human_reviewed_at", null)
            .set("started_at", null)
            .set("finished_at", null)
            .set("duration_seconds", 0);
    }

    private void applyQueuedExecutionState(ReviewTask task) {
        task.setStatus(reviewTaskStateMachine.statusWhenQueued());
        task.setRiskLevel("INFO");
        task.setPublishAttempts(0);
        task.setNextPublishRetryAt(null);
        task.setLastPublishError(null);
        task.setPublishClaimedAt(null);
        task.setPublishClaimedBy(null);
        task.setReviewClaimedAt(null);
        task.setReviewClaimedBy(null);
        task.setLlmStatus(LlmStatus.PENDING.code());
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
        task.setHumanReviewRequired(false);
        task.setHumanReviewStatus(HumanReviewStatus.NOT_REQUIRED.code());
        task.setHumanReviewNote(null);
        task.setHumanReviewBy(null);
        task.setHumanReviewedAt(null);
        task.setStartedAt(null);
        task.setFinishedAt(null);
        task.setDurationSeconds(0);
    }

    private void ensureTransitioned(int updated) {
        if (updated <= 0) {
            throw new BusinessException(ErrorCode.CONFLICT, STATE_CHANGED_MESSAGE);
        }
    }
}
