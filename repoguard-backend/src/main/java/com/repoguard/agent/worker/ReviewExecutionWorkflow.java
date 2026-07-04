package com.repoguard.agent.worker;

import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.github.GithubPullRequestDiff;
import com.repoguard.agent.messaging.ReviewTaskMessage;
import com.repoguard.agent.observability.RepoGuardMetrics;
import com.repoguard.agent.review.PullRequestReviewer;
import com.repoguard.agent.review.ReviewResult;
import com.repoguard.agent.review.ReviewTaskStateMachine;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;

@Component
class ReviewExecutionWorkflow {

    private final PullRequestReviewer pullRequestReviewer;
    private final ReviewExecutionTransactionRunner transactionRunner;
    private final RepoGuardMetrics metrics;
    private final GithubPullRequestDiffFetcher diffFetcher;
    private final ReviewTaskStateMachine reviewTaskStateMachine;
    private final ReviewTimelineAppender timelineAppender;
    private final ReviewTaskClaimService claimService;
    private final ReviewExecutionFailureHandler failureHandler;
    private final ReviewExecutionResultWriter resultWriter;
    private final ReviewExecutionNotifier notifier;
    private final ReviewExecutionDiffStats diffStats;
    private final ReviewExecutionLog executionLog;

    ReviewExecutionWorkflow(
        PullRequestReviewer pullRequestReviewer,
        ReviewExecutionTransactionRunner transactionRunner,
        RepoGuardMetrics metrics,
        GithubPullRequestDiffFetcher diffFetcher,
        ReviewTaskStateMachine reviewTaskStateMachine,
        ReviewTimelineAppender timelineAppender,
        ReviewTaskClaimService claimService,
        ReviewExecutionFailureHandler failureHandler,
        ReviewExecutionResultWriter resultWriter,
        ReviewExecutionNotifier notifier,
        ReviewExecutionLog executionLog
    ) {
        this.pullRequestReviewer = pullRequestReviewer;
        this.transactionRunner = transactionRunner;
        this.metrics = metrics;
        this.diffFetcher = diffFetcher;
        this.reviewTaskStateMachine = reviewTaskStateMachine;
        this.timelineAppender = timelineAppender;
        this.claimService = claimService;
        this.failureHandler = failureHandler;
        this.resultWriter = resultWriter;
        this.notifier = notifier;
        this.diffStats = new ReviewExecutionDiffStats();
        this.executionLog = executionLog;
    }

    void execute(ReviewTaskMessage message, ReviewTask task) {
        try (var ignored = executionLog.withExecutionContext(message, task)) {
            if (task == null) {
                executionLog.taskNotFound(message);
                return;
            }
            if (!reviewTaskStateMachine.canStartReview(task.getStatus())) {
                executionLog.statusNotQueued(task);
                return;
            }

            LocalDateTime startedAt = LocalDateTime.now();
            String claimId = claimService.newClaimId();
            if (!markReviewing(task, startedAt, claimId)) {
                executionLog.claimFailed(task);
                return;
            }
            executionLog.started(task, message);

            try {
                GithubPullRequestDiff diff = fetchPullRequestDiff(task);
                executionLog.diffFetched(task, diff, diffStats);
                ReviewResult reviewResult = pullRequestReviewer.review(task, diff);
                ReviewExecutionResultWriter.WriteResult writeResult = completeReview(
                    task,
                    diff,
                    reviewResult,
                    startedAt,
                    claimId
                );
                notifier.reviewFinished(task, writeResult.findingCount());
                executionLog.completed(task, reviewResult, writeResult, startedAt);
            } catch (ReviewTaskClaimLostException ex) {
                executionLog.resultClaimLost(task);
            } catch (RuntimeException ex) {
                if (!failReview(task, startedAt, claimId, ex)) {
                    executionLog.failureClaimLost(task, ex);
                    return;
                }
                if (metrics != null) {
                    metrics.reviewTaskFailed(ex);
                }
                executionLog.failed(task, ex, failureHandler.failureCategory(ex), startedAt);
            }
        }
    }

    private GithubPullRequestDiff fetchPullRequestDiff(ReviewTask task) {
        return diffFetcher.fetch(task);
    }

    private boolean markReviewing(ReviewTask task, LocalDateTime startedAt, String claimId) {
        return transactionRunner.execute(() -> {
            if (!claimService.claimReviewing(task, startedAt, claimId)) {
                return false;
            }
            timelineAppender.append(task.getId(), "Review started", startedAt, "CURRENT", 2);
            return true;
        });
    }

    private ReviewExecutionResultWriter.WriteResult completeReview(
        ReviewTask task,
        GithubPullRequestDiff diff,
        ReviewResult reviewResult,
        LocalDateTime startedAt,
        String claimId
    ) {
        return transactionRunner.execute(() -> resultWriter.applyCompleted(task, diff, reviewResult, startedAt, claimId));
    }

    private boolean failReview(ReviewTask task, LocalDateTime startedAt, String claimId, RuntimeException ex) {
        Boolean failed = transactionRunner.execute(() -> {
            return failureHandler.applyFailure(task, startedAt, claimId, ex);
        });
        if (Boolean.TRUE.equals(failed)) {
            notifier.reviewFailed(task);
            return true;
        }
        return false;
    }
}
