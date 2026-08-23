package com.repoguard.agent.worker;

import com.repoguard.agent.entity.ReviewExecutionAttempt;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.github.GithubPullRequestHeadChangedException;
import com.repoguard.agent.review.AssessmentStatus;
import com.repoguard.agent.review.PullRequestDiff;
import com.repoguard.agent.review.ReviewBudgetExceededException;
import com.repoguard.agent.review.ReviewDeadline;
import com.repoguard.agent.review.ReviewResult;
import com.repoguard.agent.review.execution.ReviewAttemptStageDurations;
import com.repoguard.agent.review.execution.ReviewExecutionAttemptLifecycle;
import java.time.LocalDateTime;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
class ReviewExecutionTerminalCoordinator {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReviewExecutionTerminalCoordinator.class);

    private final ReviewExecutionTransactionRunner transactionRunner;
    private final ReviewExecutionTimelineRecorder timelineRecorder;
    private final ReviewTaskClaimService claimService;
    private final ReviewExecutionFailureHandler failureHandler;
    private final ReviewExecutionSupersededHandler supersededHandler;
    private final ReviewExecutionResultWriter resultWriter;
    private final ReviewExecutionNotifier notifier;
    private final ReviewExecutionAttemptLifecycle attemptLifecycle;
    private final ReviewExecutionStageTimer stageTimer;

    @Autowired
    ReviewExecutionTerminalCoordinator(
        ReviewExecutionTransactionRunner transactionRunner,
        ReviewExecutionTimelineRecorder timelineRecorder,
        ReviewTaskClaimService claimService,
        ReviewExecutionFailureHandler failureHandler,
        ReviewExecutionSupersededHandler supersededHandler,
        ReviewExecutionResultWriter resultWriter,
        ReviewExecutionNotifier notifier,
        ReviewExecutionAttemptLifecycle attemptLifecycle,
        ReviewExecutionStageTimer stageTimer
    ) {
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner");
        this.timelineRecorder = Objects.requireNonNull(timelineRecorder, "timelineRecorder");
        this.claimService = Objects.requireNonNull(claimService, "claimService");
        this.failureHandler = Objects.requireNonNull(failureHandler, "failureHandler");
        this.supersededHandler = Objects.requireNonNull(supersededHandler, "supersededHandler");
        this.resultWriter = Objects.requireNonNull(resultWriter, "resultWriter");
        this.notifier = Objects.requireNonNull(notifier, "notifier");
        this.attemptLifecycle = Objects.requireNonNull(attemptLifecycle, "attemptLifecycle");
        this.stageTimer = Objects.requireNonNull(stageTimer, "stageTimer");
    }

    ReviewExecutionTerminalCoordinator(
        ReviewExecutionTransactionRunner transactionRunner,
        ReviewExecutionTimelineRecorder timelineRecorder,
        ReviewTaskClaimService claimService,
        ReviewExecutionFailureHandler failureHandler,
        ReviewExecutionSupersededHandler supersededHandler,
        ReviewExecutionResultWriter resultWriter,
        ReviewExecutionNotifier notifier,
        ReviewExecutionStageTimer stageTimer
    ) {
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner");
        this.timelineRecorder = Objects.requireNonNull(timelineRecorder, "timelineRecorder");
        this.claimService = Objects.requireNonNull(claimService, "claimService");
        this.failureHandler = Objects.requireNonNull(failureHandler, "failureHandler");
        this.supersededHandler = Objects.requireNonNull(supersededHandler, "supersededHandler");
        this.resultWriter = Objects.requireNonNull(resultWriter, "resultWriter");
        this.notifier = Objects.requireNonNull(notifier, "notifier");
        this.attemptLifecycle = null;
        this.stageTimer = Objects.requireNonNull(stageTimer, "stageTimer");
    }

    String newClaimId() {
        return claimService.newClaimId();
    }

    ClaimedExecution claim(
        ReviewTask task,
        LocalDateTime startedAt,
        String claimId,
        ReviewAttemptStageDurations stages,
        ReviewDeadline deadline
    ) {
        return stageTimer.record("claim", stages, () -> transactionRunner.execute(deadline, "claim", () -> {
            if (!claimService.claimReviewing(task, startedAt, claimId)) {
                return new ClaimedExecution(false, null);
            }
            timelineRecorder.reviewStarted(task, startedAt);
            ReviewExecutionAttempt attempt = attemptLifecycle == null
                ? null
                : attemptLifecycle.start(task, claimId, workerId(), startedAt);
            return new ClaimedExecution(true, attempt);
        }));
    }

    ReviewExecutionResultWriter.WriteResult complete(
        ReviewTask task,
        PullRequestDiff diff,
        ReviewResult reviewResult,
        LocalDateTime startedAt,
        String claimId,
        ReviewExecutionAttempt attempt,
        ReviewAttemptStageDurations stages,
        String budgetStage,
        ReviewDeadline deadline
    ) {
        ReviewExecutionResultWriter.WriteResult result = stageTimer.record(
            "db_write",
            stages,
            () -> transactionRunner.execute(deadline, "persist", () -> {
                ReviewExecutionResultWriter.WriteResult writeResult = resultWriter.applyCompleted(
                    task,
                    diff,
                    reviewResult,
                    startedAt,
                    claimId,
                    attempt == null ? null : attempt.getId()
                );
                completeAttempt(attempt, reviewResult, stages, writeResult.finishedAt(), budgetStage);
                notifier.reviewFinished(task, writeResult.findingCount());
                return writeResult;
            })
        );
        refreshAttemptDurations(attempt, stages);
        return result;
    }

    boolean fail(
        ReviewTask task,
        LocalDateTime startedAt,
        String claimId,
        ReviewExecutionAttempt attempt,
        ReviewAttemptStageDurations stages,
        RuntimeException failure,
        ReviewDeadline deadline
    ) {
        Boolean failed = stageTimer.record(
            "db_write",
            stages,
            () -> transactionRunner.execute(deadline, "persist_failure", () -> {
                boolean applied = failureHandler.applyFailure(task, startedAt, claimId, failure);
                if (applied) {
                    failAttempt(attempt, stages, task.getFinishedAt(), failure);
                    notifier.reviewFailed(task);
                }
                return applied;
            })
        );
        refreshAttemptDurations(attempt, stages);
        return Boolean.TRUE.equals(failed);
    }

    boolean supersede(
        ReviewTask task,
        LocalDateTime startedAt,
        String claimId,
        ReviewExecutionAttempt attempt,
        ReviewAttemptStageDurations stages,
        GithubPullRequestHeadChangedException failure,
        ReviewDeadline deadline
    ) {
        Boolean superseded = stageTimer.record(
            "db_write",
            stages,
            () -> transactionRunner.execute(deadline, "persist_superseded", () -> {
                boolean applied = supersededHandler.applySuperseded(task, startedAt, claimId, failure);
                if (applied && attemptLifecycle != null && attempt != null) {
                    attemptLifecycle.supersede(attempt, stages, task.getFinishedAt());
                }
                return applied;
            })
        );
        refreshAttemptDurations(attempt, stages);
        return Boolean.TRUE.equals(superseded);
    }

    String failureCategory(RuntimeException failure) {
        return failureHandler.failureCategory(failure);
    }

    private void completeAttempt(
        ReviewExecutionAttempt attempt,
        ReviewResult reviewResult,
        ReviewAttemptStageDurations stages,
        LocalDateTime finishedAt,
        String budgetStage
    ) {
        if (attemptLifecycle == null || attempt == null) {
            return;
        }
        boolean partial = AssessmentStatus.forCompletedReview(reviewResult) == AssessmentStatus.PARTIAL;
        attemptLifecycle.complete(
            attempt,
            reviewResult,
            stages,
            finishedAt,
            partial ? ReviewExecutionAttemptLifecycle.PARTIAL : ReviewExecutionAttemptLifecycle.COMPLETED,
            partial ? (budgetStage == null ? "INCOMPLETE_ASSESSMENT" : ReviewBudgetExceededException.CATEGORY) : null,
            budgetStage
        );
    }

    private void failAttempt(
        ReviewExecutionAttempt attempt,
        ReviewAttemptStageDurations stages,
        LocalDateTime finishedAt,
        RuntimeException failure
    ) {
        if (attemptLifecycle != null && attempt != null) {
            attemptLifecycle.fail(attempt, stages, finishedAt, failureHandler.failureCategory(failure));
        }
    }

    private void refreshAttemptDurations(ReviewExecutionAttempt attempt, ReviewAttemptStageDurations stages) {
        if (attemptLifecycle == null || attempt == null) {
            return;
        }
        try {
            attemptLifecycle.refreshDurations(attempt, stages);
        } catch (RuntimeException ex) {
            LOGGER.warn(
                "Review attempt duration refresh failed taskId={} attemptId={} operation=attempt_duration_refresh result=failed exceptionType={}",
                attempt.getTaskId(),
                attempt.getId(),
                ex.getClass().getName()
            );
        }
    }

    private String workerId() {
        String host = System.getenv("HOSTNAME");
        return StringUtils.hasText(host) ? host.trim() : "repoguard-worker";
    }

    record ClaimedExecution(boolean claimed, ReviewExecutionAttempt attempt) {
    }
}
