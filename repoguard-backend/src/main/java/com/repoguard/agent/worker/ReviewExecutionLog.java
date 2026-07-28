package com.repoguard.agent.worker;

import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.github.GithubPullRequestDiff;
import com.repoguard.agent.github.GithubPullRequestHeadChangedException;
import com.repoguard.agent.review.task.ReviewTaskMessage;
import com.repoguard.agent.observability.LogContext;
import com.repoguard.agent.review.ReviewResult;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
class ReviewExecutionLog {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReviewTaskExecutorImpl.class);

    private final ReviewExecutionClock clock;
    private final ReviewLogContextFormatter logContextFormatter;

    ReviewExecutionLog(ReviewExecutionClock clock, ReviewLogContextFormatter logContextFormatter) {
        this.clock = clock;
        this.logContextFormatter = Objects.requireNonNull(logContextFormatter, "logContextFormatter");
    }

    LogContext.Scope withExecutionContext(ReviewTaskMessage message, ReviewTask task) {
        return task == null
            ? LogContext.withReviewTaskMessage(message)
            : LogContext.withReviewTask(task);
    }

    void taskNotFound(ReviewTaskMessage message) {
        LOGGER.warn(
            "Review task skipped taskId={} repository={} prNumber={} operation=review_execute result=task_not_found",
            message.taskId(),
            logContextFormatter.repositorySlug(message),
            message.prNumber()
        );
    }

    void statusNotQueued(ReviewTask task) {
        LOGGER.info(
            "Review task skipped taskId={} repository={} prNumber={} operation=review_execute result=status_not_queued currentStatus={}",
            task.getId(),
            logContextFormatter.repositorySlug(task),
            task.getPrNumber(),
            task.getStatus()
        );
    }

    void claimFailed(ReviewTask task) {
        LOGGER.info(
            "Review task skipped taskId={} repository={} prNumber={} operation=review_execute result=claim_failed",
            task.getId(),
            logContextFormatter.repositorySlug(task),
            task.getPrNumber()
        );
    }

    void started(ReviewTask task, ReviewTaskMessage message) {
        LOGGER.info(
            "Review task started taskId={} repository={} prNumber={} operation=review_execute commit={}",
            task.getId(),
            logContextFormatter.repositorySlug(task),
            task.getPrNumber(),
            logContextFormatter.safePart(message.commit())
        );
    }

    void diffFetched(ReviewTask task, GithubPullRequestDiff diff, ReviewExecutionDiffStats diffStats) {
        LOGGER.info(
            "Review task diff fetched taskId={} repository={} prNumber={} operation=review_execute files={} additions={} deletions={} diffTruncated={} truncationReasons={}",
            task.getId(),
            logContextFormatter.repositorySlug(task),
            task.getPrNumber(),
            diffStats.fileCount(diff),
            diffStats.totalAdditions(diff),
            diffStats.totalDeletions(diff),
            diff.truncated(),
            diff.truncation().reasons()
        );
    }

    void completed(
        ReviewTask task,
        ReviewResult reviewResult,
        ReviewExecutionResultWriter.WriteResult writeResult,
        LocalDateTime startedAt
    ) {
        LOGGER.info(
            "Review task completed taskId={} repository={} prNumber={} operation=review_execute result=completed riskLevel={} llmStatus={} llmFallbackReason={} findingCount={} durationMs={} humanReviewRequired={}",
            task.getId(),
            logContextFormatter.repositorySlug(task),
            task.getPrNumber(),
            reviewResult.riskLevel(),
            reviewResult.llmStatus(),
            reviewResult.statusDetail(),
            writeResult.findingCount(),
            Duration.between(startedAt, clock.now()).toMillis(),
            writeResult.humanReviewRequired()
        );
    }

    void resultClaimLost(ReviewTask task) {
        LOGGER.warn(
            "Review task result discarded taskId={} repository={} prNumber={} operation=review_execute result=claim_lost",
            task.getId(),
            logContextFormatter.repositorySlug(task),
            task.getPrNumber()
        );
    }

    void failureClaimLost(ReviewTask task, RuntimeException ex) {
        LOGGER.warn(
            "Review task failure discarded taskId={} repository={} prNumber={} operation=review_execute result=claim_lost exceptionType={}",
            task.getId(),
            logContextFormatter.repositorySlug(task),
            task.getPrNumber(),
            ex.getClass().getName()
        );
    }

    void failed(ReviewTask task, RuntimeException ex, String failureCategory, LocalDateTime startedAt) {
        LOGGER.warn(
            "Review task failed taskId={} repository={} prNumber={} operation=review_execute result=failed failureCategory={} exceptionType={} durationMs={}",
            task.getId(),
            logContextFormatter.repositorySlug(task),
            task.getPrNumber(),
            failureCategory,
            ex.getClass().getName(),
            Duration.between(startedAt, clock.now()).toMillis()
        );
    }

    void superseded(
        ReviewTask task,
        GithubPullRequestHeadChangedException ex,
        LocalDateTime startedAt
    ) {
        LOGGER.info(
            "Review task superseded taskId={} repository={} prNumber={} operation=review_execute result=superseded expectedCommit={} currentHead={} durationMs={}",
            task.getId(),
            logContextFormatter.repositorySlug(task),
            task.getPrNumber(),
            logContextFormatter.safePart(ex.expectedHeadSha()),
            logContextFormatter.safePart(ex.currentHeadSha()),
            Duration.between(startedAt, clock.now()).toMillis()
        );
    }
}
