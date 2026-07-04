package com.repoguard.agent.worker;

import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.github.GithubPullRequestDiff;
import com.repoguard.agent.messaging.ReviewTaskMessage;
import com.repoguard.agent.observability.LogContext;
import com.repoguard.agent.review.ReviewResult;
import java.time.Duration;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
class ReviewExecutionLog {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReviewTaskExecutorImpl.class);

    private final ReviewExecutionClock clock;

    ReviewExecutionLog(ReviewExecutionClock clock) {
        this.clock = clock;
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
            messageRepositorySlug(message),
            message.prNumber()
        );
    }

    void statusNotQueued(ReviewTask task) {
        LOGGER.info(
            "Review task skipped taskId={} repository={} prNumber={} operation=review_execute result=status_not_queued currentStatus={}",
            task.getId(),
            repositorySlug(task),
            task.getPrNumber(),
            task.getStatus()
        );
    }

    void claimFailed(ReviewTask task) {
        LOGGER.info(
            "Review task skipped taskId={} repository={} prNumber={} operation=review_execute result=claim_failed",
            task.getId(),
            repositorySlug(task),
            task.getPrNumber()
        );
    }

    void started(ReviewTask task, ReviewTaskMessage message) {
        LOGGER.info(
            "Review task started taskId={} repository={} prNumber={} operation=review_execute commit={}",
            task.getId(),
            repositorySlug(task),
            task.getPrNumber(),
            safePart(message.commit())
        );
    }

    void diffFetched(ReviewTask task, GithubPullRequestDiff diff, ReviewExecutionDiffStats diffStats) {
        LOGGER.info(
            "Review task diff fetched taskId={} repository={} prNumber={} operation=review_execute files={} additions={} deletions={}",
            task.getId(),
            repositorySlug(task),
            task.getPrNumber(),
            diffStats.fileCount(diff),
            diffStats.totalAdditions(diff),
            diffStats.totalDeletions(diff)
        );
    }

    void completed(
        ReviewTask task,
        ReviewResult reviewResult,
        ReviewExecutionResultWriter.WriteResult writeResult,
        LocalDateTime startedAt
    ) {
        LOGGER.info(
            "Review task completed taskId={} repository={} prNumber={} operation=review_execute result=completed riskLevel={} llmStatus={} findingCount={} durationMs={} humanReviewRequired={}",
            task.getId(),
            repositorySlug(task),
            task.getPrNumber(),
            reviewResult.riskLevel(),
            reviewResult.llmStatus(),
            writeResult.findingCount(),
            Duration.between(startedAt, clock.now()).toMillis(),
            writeResult.humanReviewRequired()
        );
    }

    void resultClaimLost(ReviewTask task) {
        LOGGER.warn(
            "Review task result discarded taskId={} repository={} prNumber={} operation=review_execute result=claim_lost",
            task.getId(),
            repositorySlug(task),
            task.getPrNumber()
        );
    }

    void failureClaimLost(ReviewTask task, RuntimeException ex) {
        LOGGER.warn(
            "Review task failure discarded taskId={} repository={} prNumber={} operation=review_execute result=claim_lost exceptionType={}",
            task.getId(),
            repositorySlug(task),
            task.getPrNumber(),
            ex.getClass().getName()
        );
    }

    void failed(ReviewTask task, RuntimeException ex, String failureCategory, LocalDateTime startedAt) {
        LOGGER.warn(
            "Review task failed taskId={} repository={} prNumber={} operation=review_execute result=failed failureCategory={} exceptionType={} durationMs={}",
            task.getId(),
            repositorySlug(task),
            task.getPrNumber(),
            failureCategory,
            ex.getClass().getName(),
            Duration.between(startedAt, clock.now()).toMillis()
        );
    }

    String repositorySlug(ReviewTask task) {
        return safePart(task.getOrganization()) + "/" + safePart(task.getRepository());
    }

    private String messageRepositorySlug(ReviewTaskMessage message) {
        return safePart(message.organization()) + "/" + safePart(message.repository());
    }

    private String safePart(String value) {
        return value == null || value.isBlank() ? "<unknown>" : value.trim();
    }
}
