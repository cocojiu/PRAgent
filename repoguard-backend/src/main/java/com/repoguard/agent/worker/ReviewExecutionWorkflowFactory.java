package com.repoguard.agent.worker;

import com.repoguard.agent.github.GithubPullRequestClient;
import com.repoguard.agent.mapper.ChangedFileMapper;
import com.repoguard.agent.mapper.ReviewFindingMapper;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.mapper.ReviewTimelineMapper;
import com.repoguard.agent.notification.NotificationDispatchService;
import com.repoguard.agent.observability.RepoGuardMetrics;
import com.repoguard.agent.review.PullRequestReviewer;
import com.repoguard.agent.review.ReviewTaskStateMachine;
import com.repoguard.agent.review.RiskLevelRanker;
import org.springframework.transaction.PlatformTransactionManager;

class ReviewExecutionWorkflowFactory {

    ReviewExecutionWorkflow create(
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
        ReviewExecutionClock clock = new ReviewExecutionClock();
        ReviewLogContextFormatter logContextFormatter = new ReviewLogContextFormatter();
        RiskLevelRanker riskLevelRanker = new RiskLevelRanker();
        ReviewTaskStateMachine reviewTaskStateMachine = new ReviewTaskStateMachine();
        ReviewFindingDeduplicator findingDeduplicator = new ReviewFindingDeduplicator(
            new ReviewFindingDeduplicationKeyResolver(),
            new ReviewFindingMergeService(riskLevelRanker)
        );
        ReviewTimelineAppender timelineAppender = new ReviewTimelineAppender(reviewTimelineMapper);
        ReviewExecutionTimelineRecorder timelineRecorder = new ReviewExecutionTimelineRecorder(timelineAppender);
        ChangedFileReplacementService changedFileReplacementService = new ChangedFileReplacementService(
            changedFileMapper,
            new ChangedFileEntityMapper()
        );
        ReviewFindingReplacementService findingReplacementService = new ReviewFindingReplacementService(
            reviewFindingMapper,
            findingDeduplicator,
            new ReviewFindingEntityMapper()
        );
        ReviewTaskCompletionApplier completionApplier = new ReviewTaskCompletionApplier(
            reviewTaskStateMachine,
            new ReviewHumanReviewDecisionPolicy(riskLevelRanker),
            new ReviewTaskFailureOutcomePolicy(),
            new ReviewTaskDurationPolicy()
        );
        ReviewTaskClaimService claimService = new ReviewTaskClaimService(reviewTaskMapper, reviewTaskStateMachine);
        ReviewExecutionTaskTerminalWriter taskTerminalWriter = new ReviewExecutionTaskTerminalWriter(
            reviewTaskMapper,
            claimService,
            completionApplier,
            clock
        );
        ReviewExecutionMetricsRecorder metricsRecorder = new ReviewExecutionMetricsRecorder(metrics);
        ReviewExecutionFailureClassifier failureClassifier = new ReviewExecutionFailureClassifier();
        ReviewExecutionCacheInvalidator cacheInvalidator = new ReviewExecutionCacheInvalidator(
            new ReviewExecutionNoopCacheEvictionService()
        );
        ReviewExecutionFailureHandler failureHandler = new ReviewExecutionFailureHandler(
            taskTerminalWriter,
            timelineRecorder,
            metricsRecorder,
            cacheInvalidator,
            failureClassifier
        );
        ReviewExecutionResultWriter resultWriter = new ReviewExecutionResultWriter(
            taskTerminalWriter,
            changedFileReplacementService,
            findingReplacementService,
            timelineRecorder,
            metricsRecorder,
            cacheInvalidator
        );
        return new ReviewExecutionWorkflow(
            pullRequestReviewer,
            new ReviewExecutionTransactionRunner(transactionManager, 3),
            new GithubPullRequestDiffFetcher(
                githubPullRequestClient,
                metricsRecorder,
                clock,
                logContextFormatter,
                failureClassifier
            ),
            reviewTaskStateMachine,
            timelineRecorder,
            claimService,
            failureHandler,
            resultWriter,
            new ReviewExecutionNotifier(notificationDispatchServiceOrNoop(notificationDispatchService)),
            new ReviewExecutionDiffStats(),
            new ReviewExecutionLog(clock, logContextFormatter),
            clock
        );
    }

    private NotificationDispatchService notificationDispatchServiceOrNoop(
        NotificationDispatchService notificationDispatchService
    ) {
        return notificationDispatchService == null
            ? new ReviewExecutionNoopNotificationDispatchService()
            : notificationDispatchService;
    }
}
