package com.repoguard.agent.worker;

import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.review.PullRequestDiff;
import com.repoguard.agent.github.GithubPullRequestHeadChangedException;
import com.repoguard.agent.review.task.ReviewTaskMessage;
import com.repoguard.agent.review.PullRequestReviewer;
import com.repoguard.agent.review.ReviewResult;
import com.repoguard.agent.review.ReviewTaskStateMachine;
import java.time.LocalDateTime;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
class ReviewExecutionWorkflow {

    private final PullRequestReviewer pullRequestReviewer;
    private final ReviewExecutionTransactionRunner transactionRunner;
    private final GithubPullRequestDiffFetcher diffFetcher;
    private final ReviewTaskStateMachine reviewTaskStateMachine;
    private final ReviewExecutionTimelineRecorder timelineRecorder;
    private final ReviewTaskClaimService claimService;
    private final ReviewExecutionFailureHandler failureHandler;
    private final ReviewExecutionSupersededHandler supersededHandler;
    private final ReviewExecutionResultWriter resultWriter;
    private final ReviewExecutionNotifier notifier;
    private final ReviewExecutionDiffStats diffStats;
    private final ReviewExecutionLog executionLog;
    private final ReviewExecutionClock clock;
    private final ReviewExecutionStageTimer stageTimer;

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
        this.pullRequestReviewer = Objects.requireNonNull(pullRequestReviewer, "pullRequestReviewer");
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner");
        this.diffFetcher = Objects.requireNonNull(diffFetcher, "diffFetcher");
        this.reviewTaskStateMachine = Objects.requireNonNull(reviewTaskStateMachine, "reviewTaskStateMachine");
        this.timelineRecorder = Objects.requireNonNull(timelineRecorder, "timelineRecorder");
        this.claimService = Objects.requireNonNull(claimService, "claimService");
        this.failureHandler = Objects.requireNonNull(failureHandler, "failureHandler");
        this.supersededHandler = Objects.requireNonNull(supersededHandler, "supersededHandler");
        this.resultWriter = Objects.requireNonNull(resultWriter, "resultWriter");
        this.notifier = Objects.requireNonNull(notifier, "notifier");
        this.diffStats = Objects.requireNonNull(diffStats, "diffStats");
        this.executionLog = Objects.requireNonNull(executionLog, "executionLog");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.stageTimer = Objects.requireNonNull(stageTimer, "stageTimer");
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
            String claimId = claimService.newClaimId();
            if (!markReviewing(task, startedAt, claimId)) {
                executionLog.claimFailed(task);
                return;
            }
            executionLog.started(task, message);

            try {
                PullRequestDiff diff = fetchPullRequestDiff(task);
                ensureDiffMatchesTask(task, diff);
                executionLog.diffFetched(task, diff, diffStats);
                ReviewResult reviewResult = stageTimer.record("review", () -> pullRequestReviewer.review(task, diff));
                reviewResult = applyDiffBudgetOutcome(diff, reviewResult);
                ReviewExecutionResultWriter.WriteResult writeResult = completeReview(
                    task,
                    diff,
                    reviewResult,
                    startedAt,
                    claimId
                );
                executionLog.completed(task, reviewResult, writeResult, startedAt);
            } catch (GithubPullRequestHeadChangedException ex) {
                if (!supersedeReview(task, startedAt, claimId, ex)) {
                    executionLog.failureClaimLost(task, ex);
                    return;
                }
                executionLog.superseded(task, ex, startedAt);
            } catch (ReviewTaskClaimLostException ex) {
                executionLog.resultClaimLost(task);
            } catch (RuntimeException ex) {
                if (!failReview(task, startedAt, claimId, ex)) {
                    executionLog.failureClaimLost(task, ex);
                    return;
                }
                executionLog.failed(task, ex, failureHandler.failureCategory(ex), startedAt);
            }
        }
    }

    private PullRequestDiff fetchPullRequestDiff(ReviewTask task) {
        return diffFetcher.fetch(task);
    }

    private ReviewResult applyDiffBudgetOutcome(PullRequestDiff diff, ReviewResult reviewResult) {
        if (!diff.truncated()) {
            return reviewResult;
        }
        return reviewResult.withIncompleteInput(
            diff.truncation().summary(),
            "diffTruncated=true; diffTruncationReasons=" + diff.truncation().reasons().stream()
                .map(reason -> reason.code())
                .reduce((left, right) -> left + "," + right)
                .orElse("unknown")
        );
    }

    private void ensureDiffMatchesTask(ReviewTask task, PullRequestDiff diff) {
        if (diff == null) {
            throw new IllegalStateException("GitHub pull request diff is unavailable");
        }
        if (!StringUtils.hasText(task.getCommitSha())) {
            throw new IllegalStateException("Review task commit SHA is unavailable");
        }
        if (!StringUtils.hasText(diff.headSha())) {
            throw new IllegalStateException("GitHub pull request diff head SHA is unavailable");
        }
        String expectedHeadSha = task.getCommitSha().trim();
        String diffHeadSha = diff.headSha().trim();
        if (!expectedHeadSha.equalsIgnoreCase(diffHeadSha)) {
            throw new GithubPullRequestHeadChangedException(expectedHeadSha, diffHeadSha);
        }
    }

    private boolean markReviewing(ReviewTask task, LocalDateTime startedAt, String claimId) {
        return stageTimer.record("claim", () -> transactionRunner.execute(() -> {
            if (!claimService.claimReviewing(task, startedAt, claimId)) {
                return false;
            }
            timelineRecorder.reviewStarted(task, startedAt);
            return true;
        }));
    }

    private ReviewExecutionResultWriter.WriteResult completeReview(
        ReviewTask task,
        PullRequestDiff diff,
        ReviewResult reviewResult,
        LocalDateTime startedAt,
        String claimId
    ) {
        return stageTimer.record("db_write", () -> transactionRunner.execute(() -> {
            ReviewExecutionResultWriter.WriteResult writeResult =
                resultWriter.applyCompleted(task, diff, reviewResult, startedAt, claimId);
            notifier.reviewFinished(task, writeResult.findingCount());
            return writeResult;
        }));
    }

    private boolean failReview(ReviewTask task, LocalDateTime startedAt, String claimId, RuntimeException ex) {
        Boolean failed = stageTimer.record("db_write", () -> transactionRunner.execute(() -> {
            boolean applied = failureHandler.applyFailure(task, startedAt, claimId, ex);
            if (applied) {
                notifier.reviewFailed(task);
            }
            return applied;
        }));
        return Boolean.TRUE.equals(failed);
    }

    private boolean supersedeReview(
        ReviewTask task,
        LocalDateTime startedAt,
        String claimId,
        GithubPullRequestHeadChangedException ex
    ) {
        Boolean superseded = stageTimer.record("db_write", () -> transactionRunner.execute(
            () -> supersededHandler.applySuperseded(task, startedAt, claimId, ex)
        ));
        return Boolean.TRUE.equals(superseded);
    }
}
