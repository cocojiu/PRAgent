package com.repoguard.agent.worker;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.repoguard.agent.config.CacheEvictionService;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.external.ExternalCallException;
import com.repoguard.agent.github.GithubChangedFile;
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
import com.repoguard.agent.review.LlmStatus;
import com.repoguard.agent.review.ReviewResult;
import com.repoguard.agent.review.ReviewTaskStateMachine;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ReviewTaskExecutorImpl implements ReviewTaskExecutor {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReviewTaskExecutorImpl.class);

    private final ReviewTaskMapper reviewTaskMapper;
    private final PullRequestReviewer pullRequestReviewer;
    private final PlatformTransactionManager transactionManager;
    private final RepoGuardMetrics metrics;
    private final NotificationDispatchService notificationDispatchService;
    private final CacheEvictionService cacheEvictionService;
    private final GithubPullRequestDiffFetcher diffFetcher;
    private final ReviewTaskStateMachine reviewTaskStateMachine;
    private final ReviewFindingDeduplicator findingDeduplicator;
    private final ReviewTimelineAppender timelineAppender;
    private final ChangedFileReplacementService changedFileReplacementService;
    private final ReviewFindingReplacementService findingReplacementService;
    private final ReviewTaskCompletionApplier completionApplier;

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
        ReviewTaskCompletionApplier completionApplier
    ) {
        this.reviewTaskMapper = reviewTaskMapper;
        this.pullRequestReviewer = pullRequestReviewer;
        this.transactionManager = transactionManager;
        this.metrics = metrics;
        this.notificationDispatchService = notificationDispatchService;
        this.cacheEvictionService = cacheEvictionService;
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
            String claimId = UUID.randomUUID().toString();
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
                    diff.files() == null ? 0 : diff.files().size(),
                    totalAdditions(diff),
                    totalDeletions(diff)
                );
                ReviewResult reviewResult = pullRequestReviewer.review(task, diff);
                int findingCount = completeReview(task, diff, reviewResult, startedAt, claimId);
                publishReviewNotification(task, findingCount);
                LOGGER.info(
                    "Review task completed taskId={} repository={} prNumber={} operation=review_execute result=completed riskLevel={} llmStatus={} findingCount={} durationMs={} humanReviewRequired={}",
                    task.getId(),
                    repositorySlug(task),
                    task.getPrNumber(),
                    reviewResult.riskLevel(),
                    reviewResult.llmStatus(),
                    findingCount,
                    Duration.between(startedAt, LocalDateTime.now()).toMillis(),
                    completionApplier.requiresHumanReview(reviewResult.riskLevel())
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
                    failureCategory(ex),
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
        return inTransaction(() -> {
            task.setStatus(reviewTaskStateMachine.statusWhenReviewing());
            task.setStartedAt(startedAt);
            task.setReviewClaimedAt(startedAt);
            task.setReviewClaimedBy(claimId);
            int updated = reviewTaskMapper.update(
                new UpdateWrapper<ReviewTask>()
                    .eq("id", task.getId())
                    .eq("status", reviewTaskStateMachine.statusWhenQueued())
                    .set("status", reviewTaskStateMachine.statusWhenReviewing())
                    .set("started_at", startedAt)
                    .set("review_claimed_at", startedAt)
                    .set("review_claimed_by", claimId)
            );
            if (updated <= 0) {
                return false;
            }
            timelineAppender.append(task.getId(), "Review started", startedAt, "CURRENT", 2);
            return true;
        });
    }

    private int completeReview(
        ReviewTask task,
        GithubPullRequestDiff diff,
        ReviewResult reviewResult,
        LocalDateTime startedAt,
        String claimId
    ) {
        return inTransaction(() -> {
            LocalDateTime finishedAt = LocalDateTime.now();
            boolean humanReviewRequired = completionApplier.applyCompleted(task, reviewResult, startedAt, finishedAt);
            ensureClaimOwnedAndFenceTerminalStatus(task, claimId);
            task.setReviewClaimedAt(null);
            task.setReviewClaimedBy(null);
            reviewTaskMapper.updateById(task);
            changedFileReplacementService.replace(task.getId(), diff);
            timelineAppender.append(task.getId(), "GitHub diff fetched", finishedAt, "DONE", 3);
            int findingCount = findingReplacementService.replace(task.getId(), reviewResult);
            timelineAppender.append(task.getId(), reviewGeneratedLabel(reviewResult), finishedAt, "DONE", 4);
            timelineAppender.append(
                task.getId(),
                humanReviewRequired ? "Human review required" : "Review completed",
                finishedAt,
                humanReviewRequired ? "CURRENT" : "DONE",
                5
            );
            if (metrics != null) {
                metrics.reviewTaskCompleted(reviewResult.riskLevel(), reviewResult.llmStatus());
                metrics.reviewTaskDuration(Duration.between(startedAt, finishedAt), "completed");
            }
            evictDashboardOverview();
            return findingCount;
        });
    }

    private boolean failReview(ReviewTask task, LocalDateTime startedAt, String claimId, RuntimeException ex) {
        Boolean failed = inTransaction(() -> {
            LocalDateTime failedAt = LocalDateTime.now();
            completionApplier.applyFailed(task, startedAt, failedAt);
            if (!fenceTerminalStatus(task, claimId)) {
                return false;
            }
            task.setReviewClaimedAt(null);
            task.setReviewClaimedBy(null);
            reviewTaskMapper.updateById(task);
            timelineAppender.append(task.getId(), failureLabel(ex), failedAt, "FAILED", 5);
            if (metrics != null) {
                metrics.reviewTaskDuration(Duration.between(startedAt, failedAt), "failed");
            }
            evictDashboardOverview();
            return true;
        });
        if (Boolean.TRUE.equals(failed)) {
            publishReviewFailedNotification(task);
            return true;
        }
        return false;
    }

    private void ensureClaimOwnedAndFenceTerminalStatus(ReviewTask task, String claimId) {
        if (!fenceTerminalStatus(task, claimId)) {
            throw new ReviewTaskClaimLostException();
        }
    }

    private boolean fenceTerminalStatus(ReviewTask task, String claimId) {
        int updated = reviewTaskMapper.update(
            new UpdateWrapper<ReviewTask>()
                .eq("id", task.getId())
                .eq("status", reviewTaskStateMachine.statusWhenReviewing())
                .eq("review_claimed_by", claimId)
                .set("status", task.getStatus())
                .set("review_claimed_at", null)
                .set("review_claimed_by", null)
        );
        return updated > 0;
    }

    private void evictDashboardOverview() {
        if (cacheEvictionService != null) {
            cacheEvictionService.evictDashboardOverview();
        }
    }

    private void publishReviewNotification(ReviewTask task, int findingCount) {
        if (notificationDispatchService != null) {
            notificationDispatchService.reviewFinished(task, findingCount);
        }
    }

    private void publishReviewFailedNotification(ReviewTask task) {
        if (notificationDispatchService != null) {
            notificationDispatchService.reviewFailed(task);
        }
    }

    private String reviewGeneratedLabel(ReviewResult reviewResult) {
        if (LlmStatus.FALLBACK != LlmStatus.from(reviewResult.llmStatus())) {
            return "Code review generated";
        }
        String detail = reviewResult.statusDetail();
        if (detail == null || detail.isBlank()) {
            return "Code review generated by rule fallback";
        }
        return truncateLabel("Code review generated by rule fallback: " + detail.replaceAll("\\s+", " ").trim());
    }

    private String failureLabel(RuntimeException ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return "Review failed";
        }
        String normalized = message.replaceAll("\\s+", " ").trim();
        return truncateLabel("Review failed: " + normalized);
    }

    private String truncateLabel(String label) {
        return label.length() > 120 ? label.substring(0, 117) + "..." : label;
    }

    private String repositorySlug(ReviewTask task) {
        return safePart(task.getOrganization()) + "/" + safePart(task.getRepository());
    }

    private String safePart(String value) {
        return value == null || value.isBlank() ? "<unknown>" : value.trim();
    }

    private String failureCategory(RuntimeException ex) {
        if (ex instanceof ExternalCallException externalCallException) {
            return externalCallException.getCategory();
        }
        return ex.getClass().getSimpleName();
    }

    private int totalAdditions(GithubPullRequestDiff diff) {
        if (diff.files() == null) {
            return 0;
        }
        return diff.files().stream()
            .map(GithubChangedFile::additions)
            .mapToInt(value -> value == null ? 0 : value)
            .sum();
    }

    private int totalDeletions(GithubPullRequestDiff diff) {
        if (diff.files() == null) {
            return 0;
        }
        return diff.files().stream()
            .map(GithubChangedFile::deletions)
            .mapToInt(value -> value == null ? 0 : value)
            .sum();
    }

    private void inTransaction(Runnable action) {
        inTransaction(() -> {
            action.run();
            return null;
        });
    }

    private <T> T inTransaction(java.util.concurrent.Callable<T> action) {
        if (transactionManager == null) {
            try {
                return action.call();
            } catch (RuntimeException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }
        return new TransactionTemplate(transactionManager).execute(status -> {
            try {
                return action.call();
            } catch (RuntimeException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        });
    }

    private static final class ReviewTaskClaimLostException extends RuntimeException {
    }
}
