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
import com.repoguard.agent.observability.LogContext;
import com.repoguard.agent.observability.RepoGuardMetrics;
import com.repoguard.agent.review.PullRequestReviewer;
import com.repoguard.agent.review.ReviewResult;
import com.repoguard.agent.review.ReviewTaskStateMachine;
import java.time.Duration;
import java.time.LocalDateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;

@Service
public class ReviewTaskExecutorImpl implements ReviewTaskExecutor {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReviewTaskExecutorImpl.class);

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
        ReviewExecutionTransactionRunner transactionRunner
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
            null
        );
    }

    @Override
    public void execute(ReviewTaskMessage message) {
        ReviewTask task = reviewTaskMapper.selectById(message.taskId());
        try (LogContext.Scope ignored = task == null
            ? LogContext.withReviewTaskMessage(message)
            : LogContext.withReviewTask(task)) {
            if (task == null) {
                LOGGER.warn(
                    "Review task skipped taskId={} repository={}/{} prNumber={} operation=review_execute result=task_not_found",
                    message.taskId(),
                    safePart(message.organization()),
                    safePart(message.repository()),
                    message.prNumber()
                );
                return;
            }
            if (!reviewTaskStateMachine.canStartReview(task.getStatus())) {
                LOGGER.info(
                    "Review task skipped taskId={} repository={} prNumber={} operation=review_execute result=status_not_queued currentStatus={}",
                    task.getId(),
                    repositorySlug(task),
                    task.getPrNumber(),
                    task.getStatus()
                );
                return;
            }

            LocalDateTime startedAt = LocalDateTime.now();
            String claimId = claimService.newClaimId();
            if (!markReviewing(task, startedAt, claimId)) {
                LOGGER.info(
                    "Review task skipped taskId={} repository={} prNumber={} operation=review_execute result=claim_failed",
                    task.getId(),
                    repositorySlug(task),
                    task.getPrNumber()
                );
                return;
            }
            LOGGER.info(
                "Review task started taskId={} repository={} prNumber={} operation=review_execute commit={}",
                task.getId(),
                repositorySlug(task),
                task.getPrNumber(),
                safePart(message.commit())
            );

            try {
                GithubPullRequestDiff diff = fetchPullRequestDiff(task);
                LOGGER.info(
                    "Review task diff fetched taskId={} repository={} prNumber={} operation=review_execute files={} additions={} deletions={}",
                    task.getId(),
                    repositorySlug(task),
                    task.getPrNumber(),
                    diffStats.fileCount(diff),
                    diffStats.totalAdditions(diff),
                    diffStats.totalDeletions(diff)
                );
                ReviewResult reviewResult = pullRequestReviewer.review(task, diff);
                ReviewExecutionResultWriter.WriteResult writeResult = completeReview(
                    task,
                    diff,
                    reviewResult,
                    startedAt,
                    claimId
                );
                notifier.reviewFinished(task, writeResult.findingCount());
                LOGGER.info(
                    "Review task completed taskId={} repository={} prNumber={} operation=review_execute result=completed riskLevel={} llmStatus={} findingCount={} durationMs={} humanReviewRequired={}",
                    task.getId(),
                    repositorySlug(task),
                    task.getPrNumber(),
                    reviewResult.riskLevel(),
                    reviewResult.llmStatus(),
                    writeResult.findingCount(),
                    Duration.between(startedAt, LocalDateTime.now()).toMillis(),
                    writeResult.humanReviewRequired()
                );
            } catch (ReviewTaskClaimLostException ex) {
                LOGGER.warn(
                    "Review task result discarded taskId={} repository={} prNumber={} operation=review_execute result=claim_lost",
                    task.getId(),
                    repositorySlug(task),
                    task.getPrNumber()
                );
            } catch (RuntimeException ex) {
                if (!failReview(task, startedAt, claimId, ex)) {
                    LOGGER.warn(
                        "Review task failure discarded taskId={} repository={} prNumber={} operation=review_execute result=claim_lost exceptionType={}",
                        task.getId(),
                        repositorySlug(task),
                        task.getPrNumber(),
                        ex.getClass().getName()
                    );
                    return;
                }
                if (metrics != null) {
                    metrics.reviewTaskFailed(ex);
                }
                LOGGER.warn(
                    "Review task failed taskId={} repository={} prNumber={} operation=review_execute result=failed failureCategory={} exceptionType={} durationMs={}",
                    task.getId(),
                    repositorySlug(task),
                    task.getPrNumber(),
                    failureHandler.failureCategory(ex),
                    ex.getClass().getName(),
                    Duration.between(startedAt, LocalDateTime.now()).toMillis()
                );
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

    private String repositorySlug(ReviewTask task) {
        return safePart(task.getOrganization()) + "/" + safePart(task.getRepository());
    }

    private String safePart(String value) {
        return value == null || value.isBlank() ? "<unknown>" : value.trim();
    }
}
