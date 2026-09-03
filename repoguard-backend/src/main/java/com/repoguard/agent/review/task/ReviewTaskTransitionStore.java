package com.repoguard.agent.review.task;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.review.AssessmentStatus;
import com.repoguard.agent.review.HumanReviewStatus;
import com.repoguard.agent.review.LlmStatus;
import com.repoguard.agent.review.ReviewTaskStatus;
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

    public boolean assignHumanReview(
        ReviewTask task,
        String assignee,
        LocalDateTime assignedAt,
        LocalDateTime slaDeadline
    ) {
        int updated = reviewTaskMapper.update(new UpdateWrapper<ReviewTask>()
            .eq("id", task.getId())
            .eq("status", ReviewTaskStatus.PENDING_HUMAN_REVIEW.code())
            .set("review_assignee", assignee)
            .set("review_assigned_at", assignedAt)
            .set("review_sla_deadline", slaDeadline)
            .set("review_escalation_level", 0)
            .set("review_last_escalated_at", null));
        if (updated == 0) {
            return false;
        }
        task.setReviewAssignee(assignee);
        task.setReviewAssignedAt(assignedAt);
        task.setReviewSlaDeadline(slaDeadline);
        task.setReviewEscalationLevel(0);
        task.setReviewLastEscalatedAt(null);
        return true;
    }

    public boolean escalateHumanReview(ReviewTask task, int observedLevel, LocalDateTime escalatedAt) {
        return reviewTaskMapper.update(new UpdateWrapper<ReviewTask>()
            .eq("id", task.getId())
            .eq("status", ReviewTaskStatus.PENDING_HUMAN_REVIEW.code())
            .eq("review_escalation_level", observedLevel)
            .set("review_escalation_level", observedLevel + 1)
            .set("review_last_escalated_at", escalatedAt)) > 0;
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

    public void recalibrateAfterFindingFeedback(
        ReviewTask task,
        String recalculatedRiskLevel,
        boolean recalculatedHumanReviewRequired
    ) {
        Objects.requireNonNull(task, "task");
        ReviewTaskStatus observedStatus = ReviewTaskStatus.from(task.getStatus());
        boolean invalidAssessment = observedStatus == ReviewTaskStatus.FAILED
            || observedStatus == ReviewTaskStatus.SUPERSEDED
            || AssessmentStatus.FAILED.name().equalsIgnoreCase(task.getAssessmentStatus())
            || AssessmentStatus.SUPERSEDED.name().equalsIgnoreCase(task.getAssessmentStatus());
        String riskLevel = invalidAssessment ? "INFO" : recalculatedRiskLevel;
        boolean humanReviewRequired = !invalidAssessment && recalculatedHumanReviewRequired;

        UpdateWrapper<ReviewTask> update = new UpdateWrapper<ReviewTask>()
            .eq("id", task.getId())
            .eq("status", task.getStatus())
            .set("risk_level", riskLevel);
        boolean reviewGateCanChange = observedStatus == ReviewTaskStatus.COMPLETED
            || observedStatus == ReviewTaskStatus.PENDING_HUMAN_REVIEW;
        if (reviewGateCanChange) {
            update
                .set("status", reviewTaskStateMachine.statusAfterReviewCompleted(humanReviewRequired))
                .set("human_review_required", humanReviewRequired)
                .set(
                    "human_review_status",
                    HumanReviewStatus.defaultForRequired(humanReviewRequired).code()
                )
                .set("human_review_note", null)
                .set("human_review_by", null)
                .set("human_reviewed_at", null);
        }
        ensureTransitioned(reviewTaskMapper.update(update));

        task.setRiskLevel(riskLevel);
        if (reviewGateCanChange) {
            task.setStatus(reviewTaskStateMachine.statusAfterReviewCompleted(humanReviewRequired));
            task.setHumanReviewRequired(humanReviewRequired);
            task.setHumanReviewStatus(HumanReviewStatus.defaultForRequired(humanReviewRequired).code());
            task.setHumanReviewNote(null);
            task.setHumanReviewBy(null);
            task.setHumanReviewedAt(null);
        }
    }

    private UpdateWrapper<ReviewTask> resetForQueuedExecution(UpdateWrapper<ReviewTask> update) {
        return update
            .set("status", reviewTaskStateMachine.statusWhenQueued())
            .set("risk_level", "INFO")
            .set("assessment_status", AssessmentStatus.PARTIAL.name())
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
            .set("review_assignee", null)
            .set("review_assigned_at", null)
            .set("review_sla_deadline", null)
            .set("review_escalation_level", 0)
            .set("review_last_escalated_at", null)
            .set("started_at", null)
            .set("finished_at", null)
            .set("duration_seconds", 0);
    }

    private void applyQueuedExecutionState(ReviewTask task) {
        task.setStatus(reviewTaskStateMachine.statusWhenQueued());
        task.setRiskLevel("INFO");
        task.setAssessmentStatus(AssessmentStatus.PARTIAL.name());
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
        task.setReviewAssignee(null);
        task.setReviewAssignedAt(null);
        task.setReviewSlaDeadline(null);
        task.setReviewEscalationLevel(0);
        task.setReviewLastEscalatedAt(null);
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
