package com.repoguard.agent.worker;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.repoguard.agent.entity.ChangedFile;
import com.repoguard.agent.entity.ReviewFinding;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.entity.ReviewTimeline;
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
import com.repoguard.agent.review.ReviewFindingResult;
import com.repoguard.agent.review.ReviewResult;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ReviewTaskExecutorImpl implements ReviewTaskExecutor {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReviewTaskExecutorImpl.class);
    private static final String HUMAN_REVIEW_THRESHOLD = "MEDIUM";

    private final ReviewTaskMapper reviewTaskMapper;
    private final ReviewTimelineMapper reviewTimelineMapper;
    private final ReviewFindingMapper reviewFindingMapper;
    private final ChangedFileMapper changedFileMapper;
    private final GithubPullRequestClient githubPullRequestClient;
    private final PullRequestReviewer pullRequestReviewer;
    private final PlatformTransactionManager transactionManager;
    private final RepoGuardMetrics metrics;
    private final NotificationDispatchService notificationDispatchService;

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
        NotificationDispatchService notificationDispatchService
    ) {
        this.reviewTaskMapper = reviewTaskMapper;
        this.reviewTimelineMapper = reviewTimelineMapper;
        this.reviewFindingMapper = reviewFindingMapper;
        this.changedFileMapper = changedFileMapper;
        this.githubPullRequestClient = githubPullRequestClient;
        this.pullRequestReviewer = pullRequestReviewer;
        this.transactionManager = transactionManager;
        this.metrics = metrics;
        this.notificationDispatchService = notificationDispatchService;
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
            if (!"QUEUED".equals(task.getStatus())) {
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
            if (!markReviewing(task, startedAt)) {
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
                replaceChangedFiles(task.getId(), diff);
                LOGGER.info(
                    "Review task diff persisted taskId={} repository={} prNumber={} operation=review_execute files={} additions={} deletions={}",
                    task.getId(),
                    repositorySlug(task),
                    task.getPrNumber(),
                    diff.files() == null ? 0 : diff.files().size(),
                    totalAdditions(diff),
                    totalDeletions(diff)
                );
                ReviewResult reviewResult = pullRequestReviewer.review(task, diff);
                int findingCount = completeReview(task, reviewResult, startedAt);
                publishReviewNotification(task, findingCount);
                LOGGER.info(
                    "Review task completed taskId={} repository={} prNumber={} operation=review_execute result=completed riskLevel={} llmStatus={} findingCount={} durationMs={} humanReviewRequired={}",
                    task.getId(),
                    repositorySlug(task),
                    task.getPrNumber(),
                    reviewResult.riskLevel(),
                    reviewResult.llmStatus(),
                    reviewResult.findings() == null ? 0 : deduplicateFindings(reviewResult.findings()).size(),
                    Duration.between(startedAt, LocalDateTime.now()).toMillis(),
                    requiresHumanReview(reviewResult.riskLevel())
                );
            } catch (RuntimeException ex) {
                failReview(task, startedAt, ex);
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
        LocalDateTime startedAt = LocalDateTime.now();
        try {
            LOGGER.info(
                "GitHub diff fetch started taskId={} repository={} prNumber={} operation=github_diff_fetch",
                task.getId(),
                repositorySlug(task),
                task.getPrNumber()
            );
            GithubPullRequestDiff diff = githubPullRequestClient.fetchPullRequestDiff(task);
            if (metrics != null) {
                metrics.githubDiffDuration(Duration.between(startedAt, LocalDateTime.now()), "success");
            }
            LOGGER.info(
                "GitHub diff fetch completed taskId={} repository={} prNumber={} operation=github_diff_fetch result=success durationMs={} files={}",
                task.getId(),
                repositorySlug(task),
                task.getPrNumber(),
                Duration.between(startedAt, LocalDateTime.now()).toMillis(),
                diff.files() == null ? 0 : diff.files().size()
            );
            return diff;
        } catch (RuntimeException ex) {
            if (metrics != null) {
                metrics.githubDiffDuration(Duration.between(startedAt, LocalDateTime.now()), "failed");
            }
            LOGGER.warn(
                "GitHub diff fetch failed taskId={} repository={} prNumber={} operation=github_diff_fetch result=failed failureCategory={} exceptionType={} durationMs={}",
                task.getId(),
                repositorySlug(task),
                task.getPrNumber(),
                failureCategory(ex),
                ex.getClass().getName(),
                Duration.between(startedAt, LocalDateTime.now()).toMillis()
            );
            throw ex;
        }
    }

    private boolean markReviewing(ReviewTask task, LocalDateTime startedAt) {
        return inTransaction(() -> {
            task.setStatus("REVIEWING");
            task.setStartedAt(startedAt);
            int updated = reviewTaskMapper.update(
                new UpdateWrapper<ReviewTask>()
                    .eq("id", task.getId())
                    .eq("status", "QUEUED")
                    .set("status", "REVIEWING")
                    .set("started_at", startedAt)
            );
            if (updated <= 0) {
                return false;
            }
            appendTimeline(task.getId(), "Review started", startedAt, "CURRENT", 2);
            return true;
        });
    }

    private void replaceChangedFiles(Long taskId, GithubPullRequestDiff diff) {
        inTransaction(() -> {
            changedFileMapper.delete(new LambdaQueryWrapper<ChangedFile>().eq(ChangedFile::getTaskId, taskId));
            for (GithubChangedFile file : diff.files()) {
                ChangedFile changedFile = new ChangedFile();
                changedFile.setTaskId(taskId);
                changedFile.setFilePath(file.filename());
                changedFile.setChangeType(normalizeChangeType(file.status()));
                changedFile.setAdditions(file.additions() == null ? 0 : file.additions());
                changedFile.setDeletions(file.deletions() == null ? 0 : file.deletions());
                changedFileMapper.insert(changedFile);
            }
            appendTimeline(taskId, "GitHub diff fetched", LocalDateTime.now(), "DONE", 3);
        });
    }

    private int completeReview(ReviewTask task, ReviewResult reviewResult, LocalDateTime startedAt) {
        return inTransaction(() -> {
            int findingCount = deduplicateFindings(reviewResult.findings()).size();
            replaceFindings(task.getId(), reviewResult);

            LocalDateTime finishedAt = LocalDateTime.now();
            boolean humanReviewRequired = requiresHumanReview(reviewResult.riskLevel());
            task.setStatus(humanReviewRequired ? "PENDING_HUMAN_REVIEW" : "COMPLETED");
            task.setRiskLevel(reviewResult.riskLevel());
            task.setLlmStatus(reviewResult.llmStatus());
            task.setLlmProvider(reviewResult.llmProvider());
            task.setLlmModel(reviewResult.llmModel());
            task.setLlmDurationMs(reviewResult.llmDurationMs());
            task.setLlmParseStatus(reviewResult.llmParseStatus());
            task.setLlmFallbackReason(reviewResult.statusDetail());
            task.setLlmPromptSummary(reviewResult.llmPromptSummary());
            task.setLlmPromptTokens(reviewResult.llmPromptTokens());
            task.setLlmCompletionTokens(reviewResult.llmCompletionTokens());
            task.setLlmTotalTokens(reviewResult.llmTotalTokens());
            task.setLlmEstimatedCost(reviewResult.llmEstimatedCost());
            task.setHumanReviewRequired(humanReviewRequired);
            task.setHumanReviewStatus(humanReviewRequired ? "PENDING" : "NOT_REQUIRED");
            task.setHumanReviewNote(null);
            task.setHumanReviewBy(null);
            task.setHumanReviewedAt(null);
            task.setFinishedAt(finishedAt);
            task.setDurationSeconds((int) Duration.between(startedAt, finishedAt).toSeconds());
            reviewTaskMapper.updateById(task);
            appendTimeline(
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
            return findingCount;
        });
    }

    private void replaceFindings(Long taskId, ReviewResult reviewResult) {
        reviewFindingMapper.delete(new LambdaQueryWrapper<ReviewFinding>().eq(ReviewFinding::getTaskId, taskId));
        List<ReviewFindingResult> findings = deduplicateFindings(reviewResult.findings());
        for (ReviewFindingResult findingResult : findings) {
            ReviewFinding finding = new ReviewFinding();
            finding.setTaskId(taskId);
            finding.setCategory("FINDING");
            finding.setSeverity(findingResult.severity());
            finding.setSource(findingResult.source());
            finding.setRuleId(findingResult.ruleId());
            finding.setFilePath(findingResult.filePath());
            finding.setLineNumber(findingResult.lineNumber());
            finding.setMessage(findingResult.message());
            finding.setRecommendation(findingResult.recommendation());
            reviewFindingMapper.insert(finding);
        }
        appendTimeline(taskId, reviewGeneratedLabel(reviewResult), LocalDateTime.now(), "DONE", 4);
    }

    private List<ReviewFindingResult> deduplicateFindings(List<ReviewFindingResult> findings) {
        if (findings == null || findings.isEmpty()) {
            return List.of();
        }
        Map<String, ReviewFindingResult> byKey = new LinkedHashMap<>();
        for (ReviewFindingResult finding : findings) {
            String key = findingKey(finding);
            ReviewFindingResult existing = byKey.get(key);
            byKey.put(key, existing == null ? finding : mergeFinding(existing, finding));
        }
        return new ArrayList<>(byKey.values());
    }

    private String findingKey(ReviewFindingResult finding) {
        return normalizeKeyPart(finding.filePath())
            + "|" + (finding.lineNumber() == null ? "" : finding.lineNumber())
            + "|" + normalizeKeyPart(finding.message());
    }

    private ReviewFindingResult mergeFinding(ReviewFindingResult first, ReviewFindingResult second) {
        ReviewFindingResult stronger = riskRank(second.severity()) > riskRank(first.severity()) ? second : first;
        return new ReviewFindingResult(
            stronger.severity(),
            mergeSource(first.source(), second.source()),
            mergeText(first.ruleId(), second.ruleId()),
            stronger.filePath(),
            stronger.lineNumber(),
            stronger.message(),
            mergeText(first.recommendation(), second.recommendation())
        );
    }

    private String mergeSource(String first, String second) {
        String left = trimToNull(first);
        String right = trimToNull(second);
        if (left == null) {
            return right;
        }
        if (right == null || left.equalsIgnoreCase(right)) {
            return left;
        }
        if (containsSource(left, "LLM") && containsSource(right, "RULE")
            || containsSource(left, "RULE") && containsSource(right, "LLM")) {
            return "LLM+RULE";
        }
        return left + " / " + right;
    }

    private boolean containsSource(String value, String source) {
        return value != null && value.toUpperCase(Locale.ROOT).contains(source);
    }

    private String mergeText(String first, String second) {
        String left = trimToNull(first);
        String right = trimToNull(second);
        if (left == null) {
            return right;
        }
        if (right == null || left.equalsIgnoreCase(right)) {
            return left;
        }
        return left + " / " + right;
    }

    private String trimToNull(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }

    private String normalizeKeyPart(String value) {
        String trimmed = trimToNull(value);
        return trimmed == null ? "" : trimmed.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private void failReview(ReviewTask task, LocalDateTime startedAt, RuntimeException ex) {
        inTransaction(() -> {
            LocalDateTime failedAt = LocalDateTime.now();
            task.setStatus("FAILED");
            task.setRiskLevel("HIGH");
            task.setLlmStatus("FAILED");
            task.setFinishedAt(failedAt);
            task.setDurationSeconds((int) Duration.between(startedAt, failedAt).toSeconds());
            reviewTaskMapper.updateById(task);
            appendTimeline(task.getId(), failureLabel(ex), failedAt, "FAILED", 5);
            if (metrics != null) {
                metrics.reviewTaskDuration(Duration.between(startedAt, failedAt), "failed");
            }
        });
        publishReviewFailedNotification(task);
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

    private String normalizeChangeType(String status) {
        if (status == null) {
            return "MODIFY";
        }
        return switch (status.toLowerCase()) {
            case "added" -> "ADD";
            case "removed" -> "DELETE";
            case "renamed" -> "RENAME";
            default -> "MODIFY";
        };
    }

    private boolean requiresHumanReview(String riskLevel) {
        return riskRank(riskLevel) >= riskRank(HUMAN_REVIEW_THRESHOLD);
    }

    private int riskRank(String riskLevel) {
        if (riskLevel == null) {
            return 0;
        }
        return switch (riskLevel.toUpperCase()) {
            case "CRITICAL" -> 5;
            case "HIGH" -> 4;
            case "MEDIUM" -> 3;
            case "LOW" -> 2;
            case "INFO" -> 1;
            default -> 0;
        };
    }

    private String reviewGeneratedLabel(ReviewResult reviewResult) {
        if (!"FALLBACK".equals(reviewResult.llmStatus())) {
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

    private void appendTimeline(Long taskId, String label, LocalDateTime eventTime, String status, int sortOrder) {
        reviewTimelineMapper.update(
            new UpdateWrapper<ReviewTimeline>()
                .eq("task_id", taskId)
                .eq("status", "CURRENT")
                .set("status", "DONE")
        );

        ReviewTimeline timeline = new ReviewTimeline();
        timeline.setTaskId(taskId);
        timeline.setLabel(label);
        timeline.setEventTime(eventTime);
        timeline.setStatus(status);
        timeline.setSortOrder(Math.max(sortOrder, nextTimelineSortOrder(taskId)));
        reviewTimelineMapper.insert(timeline);
    }

    private int nextTimelineSortOrder(Long taskId) {
        ReviewTimeline latest = reviewTimelineMapper.selectOne(
            new LambdaQueryWrapper<ReviewTimeline>()
                .eq(ReviewTimeline::getTaskId, taskId)
                .orderByDesc(ReviewTimeline::getSortOrder)
                .last("limit 1")
        );
        return latest == null || latest.getSortOrder() == null ? 1 : latest.getSortOrder() + 1;
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
}
