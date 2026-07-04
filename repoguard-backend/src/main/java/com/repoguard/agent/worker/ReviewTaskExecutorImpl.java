package com.repoguard.agent.worker;

import com.repoguard.agent.config.CacheEvictionService;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.github.GithubPullRequestClient;
import com.repoguard.agent.github.GithubPullRequestDiff;
import com.repoguard.agent.mapper.ChangedFileMapper;
import com.repoguard.agent.mapper.ReviewFindingMapper;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.mapper.ReviewTimelineMapper;
import com.repoguard.agent.messaging.ReviewTaskMessage;
import com.repoguard.agent.notification.NotificationDispatchService;
import com.repoguard.agent.observability.RepoGuardMetrics;
import com.repoguard.agent.review.PullRequestReviewer;
import com.repoguard.agent.review.ReviewResult;
import com.repoguard.agent.review.ReviewTaskStateMachine;
import java.time.LocalDateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;

@Service
public class ReviewTaskExecutorImpl implements ReviewTaskExecutor {

    private final ReviewTaskMapper reviewTaskMapper;
    private final PullRequestReviewer pullRequestReviewer;
    private final ReviewExecutionTransactionRunner transactionRunner;
    private final RepoGuardMetrics metrics;
    private final GithubPullRequestDiffFetcher diffFetcher;
    private final ReviewTaskStateMachine reviewTaskStateMachine;
    private final ReviewFindingDeduplicator findingDeduplicator;
    private final ReviewTimelineAppender timelineAppender;
    private final ChangedFileReplacementService changedFileReplacementService;
    private final ReviewFindingReplacementService findingReplacementService;
    private final ReviewTaskCompletionApplier completionApplier;
    private final ReviewTaskClaimService claimService;
    private final ReviewExecutionFailureHandler failureHandler;
    private final ReviewExecutionResultWriter resultWriter;
    private final ReviewExecutionNotifier notifier;
    private final ReviewExecutionDiffStats diffStats;
    private final ReviewExecutionLog executionLog;

    @Autowired
    public ReviewTaskExecutorImpl(
        ReviewTaskMapper reviewTaskMapper,
        ReviewTimelineMapper reviewTimelineMapper,
        ReviewFindingMapper reviewFindingMapper,
        ChangedFileMapper changedFileMapper,
        GithubPullRequestClient githubPullRequestClient,
        PullRequestReviewer pullRequestReviewer,
        PlatformTransactionManager transactionManager,
        RepoGuardMetrics metrics,
        NotificationDispatchService notificationDispatchService,
        CacheEvictionService cacheEvictionService,
        GithubPullRequestDiffFetcher diffFetcher,
        ReviewTaskStateMachine reviewTaskStateMachine,
        ReviewFindingDeduplicator findingDeduplicator,
        ReviewTimelineAppender timelineAppender,
        ChangedFileReplacementService changedFileReplacementService,
        ReviewFindingReplacementService findingReplacementService,
        ReviewTaskCompletionApplier completionApplier,
        ReviewTaskClaimService claimService,
        ReviewExecutionFailureHandler failureHandler,
        ReviewExecutionResultWriter resultWriter,
        ReviewExecutionNotifier notifier,
        ReviewExecutionTransactionRunner transactionRunner,
        ReviewExecutionLog executionLog
    ) {
        this.reviewTaskMapper = reviewTaskMapper;
        this.pullRequestReviewer = pullRequestReviewer;
        this.transactionRunner = transactionRunner == null
            ? new ReviewExecutionTransactionRunner(transactionManager, 3)
            : transactionRunner;
        this.metrics = metrics;
        this.diffFetcher = diffFetcher == null
            ? new GithubPullRequestDiffFetcher(githubPullRequestClient, metrics)
            : diffFetcher;
        this.reviewTaskStateMachine = reviewTaskStateMachine == null
            ? new ReviewTaskStateMachine()
            : reviewTaskStateMachine;
        this.findingDeduplicator = findingDeduplicator == null
            ? new ReviewFindingDeduplicator()
            : findingDeduplicator;
        this.timelineAppender = timelineAppender == null
            ? new ReviewTimelineAppender(reviewTimelineMapper)
            : timelineAppender;
        this.changedFileReplacementService = changedFileReplacementService == null
            ? new ChangedFileReplacementService(changedFileMapper)
            : changedFileReplacementService;
        this.findingReplacementService = findingReplacementService == null
            ? new ReviewFindingReplacementService(reviewFindingMapper, this.findingDeduplicator)
            : findingReplacementService;
        this.completionApplier = completionApplier == null
            ? new ReviewTaskCompletionApplier(this.reviewTaskStateMachine)
            : completionApplier;
        this.claimService = claimService == null
            ? new ReviewTaskClaimService(reviewTaskMapper, this.reviewTaskStateMachine)
            : claimService;
        this.failureHandler = failureHandler == null
            ? new ReviewExecutionFailureHandler(
                reviewTaskMapper,
                this.claimService,
                this.completionApplier,
                this.timelineAppender,
                metrics,
                cacheEvictionService
            )
            : failureHandler;
        this.resultWriter = resultWriter == null
            ? new ReviewExecutionResultWriter(
                reviewTaskMapper,
                this.claimService,
                this.completionApplier,
                this.changedFileReplacementService,
                this.findingReplacementService,
                this.timelineAppender,
                metrics,
                cacheEvictionService
            )
            : resultWriter;
        this.notifier = notifier == null
            ? new ReviewExecutionNotifier(notificationDispatchService)
            : notifier;
        this.diffStats = new ReviewExecutionDiffStats();
        this.executionLog = executionLog == null ? new ReviewExecutionLog() : executionLog;
    }

    public ReviewTaskExecutorImpl(
        ReviewTaskMapper reviewTaskMapper,
        ReviewTimelineMapper reviewTimelineMapper,
        ReviewFindingMapper reviewFindingMapper,
        ChangedFileMapper changedFileMapper,
        GithubPullRequestClient githubPullRequestClient,
        PullRequestReviewer pullRequestReviewer,
        PlatformTransactionManager transactionManager,
        RepoGuardMetrics metrics,
        NotificationDispatchService notificationDispatchService
    ) {
        this(
            reviewTaskMapper,
            reviewTimelineMapper,
            reviewFindingMapper,
            changedFileMapper,
            githubPullRequestClient,
            pullRequestReviewer,
            transactionManager,
            metrics,
            notificationDispatchService,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null
        );
    }

    ReviewTaskExecutorImpl(
        ReviewTaskMapper reviewTaskMapper,
        ReviewTimelineMapper reviewTimelineMapper,
        ReviewFindingMapper reviewFindingMapper,
        ChangedFileMapper changedFileMapper,
        GithubPullRequestClient githubPullRequestClient,
        PullRequestReviewer pullRequestReviewer,
        PlatformTransactionManager transactionManager,
        RepoGuardMetrics metrics
    ) {
        this(
            reviewTaskMapper,
            reviewTimelineMapper,
            reviewFindingMapper,
            changedFileMapper,
            githubPullRequestClient,
            pullRequestReviewer,
            transactionManager,
            metrics,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null
        );
    }

    ReviewTaskExecutorImpl(
        ReviewTaskMapper reviewTaskMapper,
        ReviewTimelineMapper reviewTimelineMapper,
        ReviewFindingMapper reviewFindingMapper,
        ChangedFileMapper changedFileMapper,
        GithubPullRequestClient githubPullRequestClient,
        PullRequestReviewer pullRequestReviewer
    ) {
        this(
            reviewTaskMapper,
            reviewTimelineMapper,
            reviewFindingMapper,
            changedFileMapper,
            githubPullRequestClient,
            pullRequestReviewer,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null
        );
    }

    @Override
    public void execute(ReviewTaskMessage message) {
        ReviewTask task = reviewTaskMapper.selectById(message.taskId());
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
