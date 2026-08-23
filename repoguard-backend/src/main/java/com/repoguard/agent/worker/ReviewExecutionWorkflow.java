package com.repoguard.agent.worker;

import com.repoguard.agent.entity.ReviewExecutionAttempt;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.github.GithubPullRequestHeadChangedException;
import com.repoguard.agent.review.PullRequestDiff;
import com.repoguard.agent.review.PullRequestReviewer;
import com.repoguard.agent.review.ReviewBudgetExceededException;
import com.repoguard.agent.review.ReviewDeadline;
import com.repoguard.agent.review.ReviewResult;
import com.repoguard.agent.review.ReviewTaskStateMachine;
import com.repoguard.agent.review.execution.ReviewAttemptStageDurations;
import com.repoguard.agent.review.execution.ReviewExecutionBudgetProperties;
import com.repoguard.agent.review.task.ReviewTaskMessage;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
class ReviewExecutionWorkflow {

    private final ReviewExecutionReviewProcessor reviewProcessor;
    private final ReviewExecutionTerminalCoordinator terminalCoordinator;
    private final GithubPullRequestDiffFetcher diffFetcher;
    private final ReviewTaskStateMachine reviewTaskStateMachine;
    private final ReviewExecutionDiffStats diffStats;
    private final ReviewExecutionLog executionLog;
    private final ReviewExecutionClock clock;
    private final ReviewExecutionStageTimer stageTimer;
    private final long executionBudgetMs;
    private final long persistenceReserveMs;
    private final long terminalPersistenceGraceMs;

    @Autowired
    ReviewExecutionWorkflow(
        ReviewExecutionReviewProcessor reviewProcessor,
        ReviewExecutionTerminalCoordinator terminalCoordinator,
        GithubPullRequestDiffFetcher diffFetcher,
        ReviewTaskStateMachine reviewTaskStateMachine,
        ReviewExecutionDiffStats diffStats,
        ReviewExecutionLog executionLog,
        ReviewExecutionClock clock,
        ReviewExecutionStageTimer stageTimer,
        ReviewExecutionBudgetProperties budgetProperties
    ) {
        this.reviewProcessor = Objects.requireNonNull(reviewProcessor, "reviewProcessor");
        this.terminalCoordinator = Objects.requireNonNull(terminalCoordinator, "terminalCoordinator");
        this.diffFetcher = Objects.requireNonNull(diffFetcher, "diffFetcher");
        this.reviewTaskStateMachine = Objects.requireNonNull(reviewTaskStateMachine, "reviewTaskStateMachine");
        this.diffStats = Objects.requireNonNull(diffStats, "diffStats");
        this.executionLog = Objects.requireNonNull(executionLog, "executionLog");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.stageTimer = Objects.requireNonNull(stageTimer, "stageTimer");
        ReviewExecutionBudgetProperties executionBudgetProperties = Objects.requireNonNull(
            budgetProperties,
            "budgetProperties"
        );
        this.executionBudgetMs = executionBudgetProperties.getBudgetMs();
        this.persistenceReserveMs = executionBudgetProperties.getPersistenceReserveMs();
        this.terminalPersistenceGraceMs = executionBudgetProperties.getTerminalPersistenceGraceMs();
    }

    ReviewExecutionWorkflow(
        PullRequestReviewer pullRequestReviewer,
        ReviewExecutionTransactionRunner transactionRunner,
        GithubPullRequestDiffFetcher diffFetcher,
        ReviewTaskStateMachine reviewTaskStateMachine,
        ReviewExecutionTimelineRecorder timelineRecorder,
        ReviewTaskClaimService claimService,
        ReviewExecutionFailureHandler failureHandler,
        ReviewExecutionSupersededHandler supersededHandler,
        ReviewExecutionResultWriter resultWriter,
        ReviewExecutionNotifier notifier,
        ReviewExecutionDiffStats diffStats,
        ReviewExecutionLog executionLog,
        ReviewExecutionClock clock,
        ReviewExecutionStageTimer stageTimer
    ) {
        this.reviewProcessor = new ReviewExecutionReviewProcessor(pullRequestReviewer);
        this.terminalCoordinator = new ReviewExecutionTerminalCoordinator(
            transactionRunner,
            timelineRecorder,
            claimService,
            failureHandler,
            supersededHandler,
            resultWriter,
            notifier,
            stageTimer
        );
        this.diffFetcher = Objects.requireNonNull(diffFetcher, "diffFetcher");
        this.reviewTaskStateMachine = Objects.requireNonNull(reviewTaskStateMachine, "reviewTaskStateMachine");
        this.diffStats = Objects.requireNonNull(diffStats, "diffStats");
        this.executionLog = Objects.requireNonNull(executionLog, "executionLog");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.stageTimer = Objects.requireNonNull(stageTimer, "stageTimer");
        this.executionBudgetMs = 600_000L;
        this.persistenceReserveMs = 30_000L;
        this.terminalPersistenceGraceMs = 5_000L;
    }

    void execute(ReviewTaskMessage message, ReviewTask task) {
        try (var _ = executionLog.withExecutionContext(message, task)) {
            if (task == null) {
                executionLog.taskNotFound(message);
                return;
            }
            if (!reviewTaskStateMachine.canStartReview(task.getStatus())) {
                executionLog.statusNotQueued(task);
                return;
            }

            LocalDateTime startedAt = clock.now();
            ReviewDeadline executionDeadline = ReviewDeadline.startingNow(Duration.ofMillis(executionBudgetMs));
            ReviewDeadline workDeadline = executionDeadline.reserving(Duration.ofMillis(persistenceReserveMs));
            ReviewDeadline terminalDeadline = executionDeadline.extending(Duration.ofMillis(terminalPersistenceGraceMs));
            ReviewAttemptStageDurations stages = new ReviewAttemptStageDurations();
            String claimId = terminalCoordinator.newClaimId();
            ReviewExecutionTerminalCoordinator.ClaimedExecution claimed = terminalCoordinator.claim(
                task,
                startedAt,
                claimId,
                stages,
                executionDeadline
            );
            if (!claimed.claimed()) {
                executionLog.claimFailed(task);
                return;
            }
            ReviewExecutionAttempt attempt = claimed.attempt();
            executionLog.started(task, message);

            PullRequestDiff diff = null;
            try {
                workDeadline.requireRemaining("diff_fetch");
                diff = stageTimer.record("diff_fetch", stages, () -> diffFetcher.fetch(task));
                workDeadline.requireRemaining("review");
                reviewProcessor.ensureDiffMatchesTask(task, diff);
                executionLog.diffFetched(task, diff, diffStats);
                PullRequestDiff fetchedDiff = diff;
                ReviewResult reviewResult = stageTimer.record(
                    "review",
                    stages,
                    () -> reviewProcessor.review(task, fetchedDiff, workDeadline)
                );
                reviewResult = reviewProcessor.applyDiffBudgetOutcome(diff, reviewResult);
                String budgetStage = reviewProcessor.budgetStage(reviewResult);
                if (executionDeadline.exhausted() && budgetStage == null) {
                    budgetStage = "persist";
                    reviewResult = reviewResult.withIncompleteInput(
                        ReviewBudgetExceededException.CATEGORY + ":persist",
                        "executionBudgetExceededStage=persist"
                    );
                }
                ReviewExecutionResultWriter.WriteResult writeResult = terminalCoordinator.complete(
                    task,
                    diff,
                    reviewResult,
                    startedAt,
                    claimId,
                    attempt,
                    stages,
                    budgetStage,
                    terminalDeadline
                );
                executionLog.completed(task, reviewResult, writeResult, startedAt);
            } catch (GithubPullRequestHeadChangedException ex) {
                if (!terminalCoordinator.supersede(task, startedAt, claimId, attempt, stages, ex, terminalDeadline)) {
                    executionLog.failureClaimLost(task, ex);
                    return;
                }
                executionLog.superseded(task, ex, startedAt);
            } catch (ReviewBudgetExceededException ex) {
                PullRequestDiff retainedDiff = diff == null ? reviewProcessor.emptyDiff(task) : diff;
                ReviewResult partial = ReviewResult.fallback("INFO", ex.getMessage(), List.of())
                    .withIncompleteInput(ex.getMessage(), "executionBudgetExceededStage=" + ex.stage());
                ReviewExecutionResultWriter.WriteResult writeResult = terminalCoordinator.complete(
                    task,
                    retainedDiff,
                    partial,
                    startedAt,
                    claimId,
                    attempt,
                    stages,
                    ex.stage(),
                    terminalDeadline
                );
                executionLog.completed(task, partial, writeResult, startedAt);
            } catch (ReviewTaskClaimLostException ex) {
                executionLog.resultClaimLost(task);
            } catch (RuntimeException ex) {
                if (!terminalCoordinator.fail(task, startedAt, claimId, attempt, stages, ex, terminalDeadline)) {
                    executionLog.failureClaimLost(task, ex);
                    return;
                }
                executionLog.failed(task, ex, terminalCoordinator.failureCategory(ex), startedAt);
            }
        }
    }
}
