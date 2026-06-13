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
import com.repoguard.agent.observability.RepoGuardMetrics;
import com.repoguard.agent.review.PullRequestReviewer;
import com.repoguard.agent.review.ReviewFindingResult;
import com.repoguard.agent.review.ReviewResult;
import java.time.Duration;
import java.time.LocalDateTime;
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

    @Autowired
    public ReviewTaskExecutorImpl(
        ReviewTaskMapper reviewTaskMapper,
        ReviewTimelineMapper reviewTimelineMapper,
        ReviewFindingMapper reviewFindingMapper,
        ChangedFileMapper changedFileMapper,
        GithubPullRequestClient githubPullRequestClient,
        PullRequestReviewer pullRequestReviewer,
        PlatformTransactionManager transactionManager,
        RepoGuardMetrics metrics
    ) {
        this.reviewTaskMapper = reviewTaskMapper;
        this.reviewTimelineMapper = reviewTimelineMapper;
        this.reviewFindingMapper = reviewFindingMapper;
        this.changedFileMapper = changedFileMapper;
        this.githubPullRequestClient = githubPullRequestClient;
        this.pullRequestReviewer = pullRequestReviewer;
        this.transactionManager = transactionManager;
        this.metrics = metrics;
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
            null
        );
    }

    @Override
    public void execute(ReviewTaskMessage message) {
        ReviewTask task = reviewTaskMapper.selectById(message.taskId());
        if (task == null || !"QUEUED".equals(task.getStatus())) {
            return;
        }

        LocalDateTime startedAt = LocalDateTime.now();
        if (!markReviewing(task, startedAt)) {
            return;
        }
        LOGGER.info(
            "Review task started taskId={} repository={} prNumber={} operation=review_execute",
            task.getId(),
            repositorySlug(task),
            task.getPrNumber()
        );

        try {
            GithubPullRequestDiff diff = fetchPullRequestDiff(task);
            replaceChangedFiles(task.getId(), diff);
            ReviewResult reviewResult = pullRequestReviewer.review(task, diff);
            completeReview(task, reviewResult, startedAt);
            LOGGER.info(
                "Review task completed taskId={} repository={} prNumber={} operation=review_execute riskLevel={} llmStatus={}",
                task.getId(),
                repositorySlug(task),
                task.getPrNumber(),
                reviewResult.riskLevel(),
                reviewResult.llmStatus()
            );
        } catch (RuntimeException ex) {
            failReview(task, startedAt, ex);
            if (metrics != null) {
                metrics.reviewTaskFailed(ex);
            }
            LOGGER.warn(
                "Review task failed taskId={} repository={} prNumber={} operation=review_execute failureCategory={} exceptionType={}",
                task.getId(),
                repositorySlug(task),
                task.getPrNumber(),
                failureCategory(ex),
                ex.getClass().getName()
            );
        }
    }

    private GithubPullRequestDiff fetchPullRequestDiff(ReviewTask task) {
        LocalDateTime startedAt = LocalDateTime.now();
        try {
            GithubPullRequestDiff diff = githubPullRequestClient.fetchPullRequestDiff(task);
            if (metrics != null) {
                metrics.githubDiffDuration(Duration.between(startedAt, LocalDateTime.now()), "success");
            }
            return diff;
        } catch (RuntimeException ex) {
            if (metrics != null) {
                metrics.githubDiffDuration(Duration.between(startedAt, LocalDateTime.now()), "failed");
            }
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

    private void completeReview(ReviewTask task, ReviewResult reviewResult, LocalDateTime startedAt) {
        inTransaction(() -> {
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
        });
    }

    private void replaceFindings(Long taskId, ReviewResult reviewResult) {
        reviewFindingMapper.delete(new LambdaQueryWrapper<ReviewFinding>().eq(ReviewFinding::getTaskId, taskId));
        for (ReviewFindingResult findingResult : reviewResult.findings()) {
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
