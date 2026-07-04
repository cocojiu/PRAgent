package com.repoguard.agent.worker;

import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.github.GithubPullRequestClient;
import com.repoguard.agent.mapper.ChangedFileMapper;
import com.repoguard.agent.mapper.ReviewFindingMapper;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.mapper.ReviewTimelineMapper;
import com.repoguard.agent.messaging.ReviewTaskMessage;
import com.repoguard.agent.notification.NotificationDispatchService;
import com.repoguard.agent.observability.RepoGuardMetrics;
import com.repoguard.agent.review.PullRequestReviewer;
import com.repoguard.agent.review.ReviewTaskStateMachine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;

@Service
public class ReviewTaskExecutorImpl implements ReviewTaskExecutor {

    private final ReviewTaskMapper reviewTaskMapper;
    private final ReviewExecutionWorkflow workflow;

    @Autowired
    public ReviewTaskExecutorImpl(ReviewTaskMapper reviewTaskMapper, ReviewExecutionWorkflow workflow) {
        this.reviewTaskMapper = reviewTaskMapper;
        this.workflow = workflow;
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
            createWorkflow(
                reviewTaskMapper,
                reviewTimelineMapper,
                reviewFindingMapper,
                changedFileMapper,
                githubPullRequestClient,
                pullRequestReviewer,
                transactionManager,
                metrics,
                notificationDispatchService
            )
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
            null
        );
    }

    @Override
    public void execute(ReviewTaskMessage message) {
        ReviewTask task = reviewTaskMapper.selectById(message.taskId());
        workflow.execute(message, task);
    }

    private static ReviewExecutionWorkflow createWorkflow(
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
        ReviewTaskStateMachine reviewTaskStateMachine = new ReviewTaskStateMachine();
        ReviewFindingDeduplicator findingDeduplicator = new ReviewFindingDeduplicator();
        ReviewTimelineAppender timelineAppender = new ReviewTimelineAppender(reviewTimelineMapper);
        ReviewExecutionTimelineRecorder timelineRecorder = new ReviewExecutionTimelineRecorder(timelineAppender);
        ChangedFileReplacementService changedFileReplacementService = new ChangedFileReplacementService(changedFileMapper);
        ReviewFindingReplacementService findingReplacementService = new ReviewFindingReplacementService(
            reviewFindingMapper,
            findingDeduplicator
        );
        ReviewTaskCompletionApplier completionApplier = new ReviewTaskCompletionApplier(reviewTaskStateMachine);
        ReviewTaskClaimService claimService = new ReviewTaskClaimService(reviewTaskMapper, reviewTaskStateMachine);
        ReviewExecutionMetricsRecorder metricsRecorder = new ReviewExecutionMetricsRecorder(metrics);
        ReviewExecutionCacheInvalidator cacheInvalidator = new ReviewExecutionCacheInvalidator(null);
        ReviewExecutionFailureHandler failureHandler = new ReviewExecutionFailureHandler(
            reviewTaskMapper,
            claimService,
            completionApplier,
            timelineRecorder,
            metricsRecorder,
            cacheInvalidator
        );
        ReviewExecutionResultWriter resultWriter = new ReviewExecutionResultWriter(
            reviewTaskMapper,
            claimService,
            completionApplier,
            changedFileReplacementService,
            findingReplacementService,
            timelineRecorder,
            metricsRecorder,
            cacheInvalidator
        );
        return new ReviewExecutionWorkflow(
            pullRequestReviewer,
            new ReviewExecutionTransactionRunner(transactionManager, 3),
            new GithubPullRequestDiffFetcher(githubPullRequestClient, metrics),
            reviewTaskStateMachine,
            timelineRecorder,
            claimService,
            failureHandler,
            resultWriter,
            new ReviewExecutionNotifier(notificationDispatchService),
            new ReviewExecutionLog()
        );
    }
}
