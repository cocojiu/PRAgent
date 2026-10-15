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
import java.util.List;
import java.util.ArrayList;
import java.util.function.BiConsumer;
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
        UpdateWrapper<ReviewTask> update = new UpdateWrapper<ReviewTask>()
            .eq("id", task.getId()).eq("status", observedStatus);
        if (StringUtils.hasText(observedCommitSha)) update.eq("commit_sha", observedCommitSha);
        else update.isNull("commit_sha");
        List<Change<?>> changes = new ArrayList<>(queuedChanges());
        changes.add(change("mq_retries", retryCount, ReviewTask::setMqRetries));
        changes.add(change("commit_sha", replacementCommitSha.trim(), ReviewTask::setCommitSha));
        transition(task, update, changes).requireApplied();
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
        UpdateWrapper<ReviewTask> update = new UpdateWrapper<ReviewTask>()
            .eq("id", task.getId())
            .eq("status", reviewTaskStateMachine.statusAfterReviewCompleted(true))
            .eq("human_review_required", true)
            .eq("human_review_status", HumanReviewStatus.PENDING.code());
        transition(task, update, List.of(
            change("status", taskStatus, ReviewTask::setStatus),
            change("human_review_status", humanReviewStatus, ReviewTask::setHumanReviewStatus),
            change("human_review_note", note, ReviewTask::setHumanReviewNote),
            change("human_review_by", operator, ReviewTask::setHumanReviewBy),
            change("human_reviewed_at", reviewedAt, ReviewTask::setHumanReviewedAt)
        )).requireApplied();
    }

    public boolean assignHumanReview(
        ReviewTask task,
        String assignee,
        LocalDateTime assignedAt,
        LocalDateTime slaDeadline
    ) {
        UpdateWrapper<ReviewTask> update = new UpdateWrapper<ReviewTask>()
            .eq("id", task.getId())
            .eq("status", ReviewTaskStatus.PENDING_HUMAN_REVIEW.code())
            .eq(task.getCurrentAttemptId() != null, "current_attempt_id", task.getCurrentAttemptId())
            .isNull(task.getCurrentAttemptId() == null, "current_attempt_id")
            .eq(task.getCommitSha() != null, "commit_sha", task.getCommitSha())
            .isNull(task.getCommitSha() == null, "commit_sha")
            .eq(task.getGeneration() != null, "generation", task.getGeneration())
            .isNull(task.getGeneration() == null, "generation")
            .eq(task.getReviewAssignee() != null, "review_assignee", task.getReviewAssignee())
            .isNull(task.getReviewAssignee() == null, "review_assignee")
            .eq(task.getReviewAssignedAt() != null, "review_assigned_at", task.getReviewAssignedAt())
            .isNull(task.getReviewAssignedAt() == null, "review_assigned_at")
            .eq(task.getReviewSlaDeadline() != null, "review_sla_deadline", task.getReviewSlaDeadline())
            .isNull(task.getReviewSlaDeadline() == null, "review_sla_deadline");
        return transition(task, update, List.of(
            change("review_assignee", assignee, ReviewTask::setReviewAssignee),
            change("review_assigned_at", assignedAt, ReviewTask::setReviewAssignedAt),
            change("review_sla_deadline", slaDeadline, ReviewTask::setReviewSlaDeadline),
            change("review_escalation_level", 0, ReviewTask::setReviewEscalationLevel),
            change("review_last_escalated_at", null, ReviewTask::setReviewLastEscalatedAt)
        )).applied();
    }

    public boolean escalateHumanReview(ReviewTask task, int observedLevel, LocalDateTime escalatedAt,
                                      LocalDateTime cooldownBefore, int limit) {
        LocalDateTime deadline = task.getReviewSlaDeadline();
        LocalDateTime previous = task.getReviewLastEscalatedAt();
        if (observedLevel >= limit || deadline == null || deadline.isAfter(escalatedAt)
            || previous != null && previous.isAfter(cooldownBefore)) {
            return false;
        }
        UpdateWrapper<ReviewTask> update = new UpdateWrapper<ReviewTask>()
            .eq("id", task.getId())
            .eq("status", ReviewTaskStatus.PENDING_HUMAN_REVIEW.code())
            .eq("human_review_status", HumanReviewStatus.PENDING.code())
            .eq("review_escalation_level", observedLevel)
            .lt("review_escalation_level", limit)
            .eq("review_sla_deadline", deadline)
            .le("review_sla_deadline", escalatedAt)
            .eq(task.getReviewAssignee() != null, "review_assignee", task.getReviewAssignee())
            .isNull(task.getReviewAssignee() == null, "review_assignee")
            .eq(task.getReviewAssignedAt() != null, "review_assigned_at", task.getReviewAssignedAt())
            .isNull(task.getReviewAssignedAt() == null, "review_assigned_at")
            .eq(previous != null, "review_last_escalated_at", previous)
            .isNull(previous == null, "review_last_escalated_at")
            .and(query -> query.isNull("review_last_escalated_at").or().le("review_last_escalated_at", cooldownBefore));
        return transition(task, update, List.of(
            change("review_escalation_level", observedLevel + 1, ReviewTask::setReviewEscalationLevel),
            change("review_last_escalated_at", escalatedAt, ReviewTask::setReviewLastEscalatedAt)
        )).applied();
    }

    public void requeueForPublish(ReviewTask task) {
        Objects.requireNonNull(task, "task");
        transition(task, new UpdateWrapper<ReviewTask>().eq("id", task.getId())
            .eq("status", task.getStatus()).isNull("publish_claimed_at"), queuedChanges()).requireApplied();
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

        List<Change<?>> changes = new ArrayList<>();
        changes.add(change("risk_level", riskLevel, ReviewTask::setRiskLevel));
        if (observedStatus == ReviewTaskStatus.COMPLETED || observedStatus == ReviewTaskStatus.PENDING_HUMAN_REVIEW) {
            changes.addAll(List.of(
                change("status", reviewTaskStateMachine.statusAfterReviewCompleted(humanReviewRequired), ReviewTask::setStatus),
                change("human_review_required", humanReviewRequired, ReviewTask::setHumanReviewRequired),
                change("human_review_status", HumanReviewStatus.defaultForRequired(humanReviewRequired).code(), ReviewTask::setHumanReviewStatus),
                change("human_review_note", null, ReviewTask::setHumanReviewNote),
                change("human_review_by", null, ReviewTask::setHumanReviewBy),
                change("human_reviewed_at", null, ReviewTask::setHumanReviewedAt)
            ));
        }
        transition(task, new UpdateWrapper<ReviewTask>().eq("id", task.getId()).eq("status", task.getStatus()), changes).requireApplied();
    }

    /** One definition drives both SQL and the in-memory state, after a successful CAS. */
    private List<Change<?>> queuedChanges() {
        return List.of(
            change("status", reviewTaskStateMachine.statusWhenQueued(), ReviewTask::setStatus),
            change("risk_level", "INFO", ReviewTask::setRiskLevel),
            change("assessment_status", AssessmentStatus.PARTIAL.name(), ReviewTask::setAssessmentStatus),
            change("failure_details_json", null, ReviewTask::setFailureDetailsJson),
            change("publish_attempts", 0, ReviewTask::setPublishAttempts),
            change("next_publish_retry_at", null, ReviewTask::setNextPublishRetryAt),
            change("last_publish_error", null, ReviewTask::setLastPublishError),
            change("publish_claimed_at", null, ReviewTask::setPublishClaimedAt),
            change("publish_claimed_by", null, ReviewTask::setPublishClaimedBy),
            change("review_claimed_at", null, ReviewTask::setReviewClaimedAt),
            change("review_claimed_by", null, ReviewTask::setReviewClaimedBy),
            change("llm_status", LlmStatus.PENDING.code(), ReviewTask::setLlmStatus),
            change("llm_provider", null, ReviewTask::setLlmProvider),
            change("llm_model", null, ReviewTask::setLlmModel),
            change("llm_duration_ms", null, ReviewTask::setLlmDurationMs),
            change("llm_parse_status", null, ReviewTask::setLlmParseStatus),
            change("llm_fallback_reason", null, ReviewTask::setLlmFallbackReason),
            change("llm_prompt_summary", null, ReviewTask::setLlmPromptSummary),
            change("llm_prompt_tokens", null, ReviewTask::setLlmPromptTokens),
            change("llm_completion_tokens", null, ReviewTask::setLlmCompletionTokens),
            change("llm_total_tokens", null, ReviewTask::setLlmTotalTokens),
            change("llm_estimated_cost", null, ReviewTask::setLlmEstimatedCost),
            change("llm_cost_snapshot_json", null, ReviewTask::setLlmCostSnapshotJson),
            change("human_review_required", false, ReviewTask::setHumanReviewRequired),
            change("human_review_status", HumanReviewStatus.NOT_REQUIRED.code(), ReviewTask::setHumanReviewStatus),
            change("human_review_note", null, ReviewTask::setHumanReviewNote),
            change("human_review_by", null, ReviewTask::setHumanReviewBy),
            change("human_reviewed_at", null, ReviewTask::setHumanReviewedAt),
            change("review_assignee", null, ReviewTask::setReviewAssignee),
            change("review_assigned_at", null, ReviewTask::setReviewAssignedAt),
            change("review_sla_deadline", null, ReviewTask::setReviewSlaDeadline),
            change("review_escalation_level", 0, ReviewTask::setReviewEscalationLevel),
            change("review_last_escalated_at", null, ReviewTask::setReviewLastEscalatedAt),
            change("started_at", null, ReviewTask::setStartedAt),
            change("finished_at", null, ReviewTask::setFinishedAt),
            change("duration_seconds", 0, ReviewTask::setDurationSeconds)
        );
    }

    private TransitionResult transition(ReviewTask task, UpdateWrapper<ReviewTask> expected, List<Change<?>> changes) {
        changes.forEach(change -> expected.set(change.column(), change.value()));
        TransitionResult result = new TransitionResult(reviewTaskMapper.update(expected));
        if (result.applied()) changes.forEach(change -> change.applyTo(task));
        return result;
    }

    private static <T> Change<T> change(String column, T value, BiConsumer<ReviewTask, T> setter) {
        return new Change<>(column, value, setter);
    }

    private record Change<T>(String column, T value, BiConsumer<ReviewTask, T> setter) {
        void applyTo(ReviewTask task) { setter.accept(task, value); }
    }

    private record TransitionResult(int affectedRows) {
        boolean applied() { return affectedRows == 1; }
        void requireApplied() {
            if (!applied()) throw new BusinessException(ErrorCode.CONFLICT, STATE_CHANGED_MESSAGE);
        }
    }
}
