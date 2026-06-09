package com.repoguard.agent.worker;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.repoguard.agent.entity.ChangedFile;
import com.repoguard.agent.entity.ReviewFinding;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.entity.ReviewTimeline;
import com.repoguard.agent.github.GithubChangedFile;
import com.repoguard.agent.github.GithubPullRequestClient;
import com.repoguard.agent.github.GithubPullRequestDiff;
import com.repoguard.agent.mapper.ChangedFileMapper;
import com.repoguard.agent.mapper.ReviewFindingMapper;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.mapper.ReviewTimelineMapper;
import com.repoguard.agent.messaging.ReviewTaskMessage;
import com.repoguard.agent.review.PullRequestReviewer;
import com.repoguard.agent.review.ReviewFindingResult;
import com.repoguard.agent.review.ReviewResult;
import java.time.Duration;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReviewTaskExecutorImpl implements ReviewTaskExecutor {

    private final ReviewTaskMapper reviewTaskMapper;
    private final ReviewTimelineMapper reviewTimelineMapper;
    private final ReviewFindingMapper reviewFindingMapper;
    private final ChangedFileMapper changedFileMapper;
    private final GithubPullRequestClient githubPullRequestClient;
    private final PullRequestReviewer pullRequestReviewer;

    public ReviewTaskExecutorImpl(
        ReviewTaskMapper reviewTaskMapper,
        ReviewTimelineMapper reviewTimelineMapper,
        ReviewFindingMapper reviewFindingMapper,
        ChangedFileMapper changedFileMapper,
        GithubPullRequestClient githubPullRequestClient,
        PullRequestReviewer pullRequestReviewer
    ) {
        this.reviewTaskMapper = reviewTaskMapper;
        this.reviewTimelineMapper = reviewTimelineMapper;
        this.reviewFindingMapper = reviewFindingMapper;
        this.changedFileMapper = changedFileMapper;
        this.githubPullRequestClient = githubPullRequestClient;
        this.pullRequestReviewer = pullRequestReviewer;
    }

    @Override
    @Transactional
    public void execute(ReviewTaskMessage message) {
        ReviewTask task = reviewTaskMapper.selectById(message.taskId());
        if (task == null || "COMPLETED".equals(task.getStatus())) {
            return;
        }

        LocalDateTime startedAt = LocalDateTime.now();
        task.setStatus("REVIEWING");
        task.setStartedAt(startedAt);
        reviewTaskMapper.updateById(task);
        appendTimeline(task.getId(), "Review started", startedAt, "CURRENT", 2);

        try {
            GithubPullRequestDiff diff = githubPullRequestClient.fetchPullRequestDiff(task);
            replaceChangedFiles(task.getId(), diff);
            ReviewResult reviewResult = pullRequestReviewer.review(task, diff);
            replaceFindings(task.getId(), reviewResult);

            LocalDateTime finishedAt = LocalDateTime.now();
            task.setStatus("COMPLETED");
            task.setRiskLevel(reviewResult.riskLevel());
            task.setLlmStatus(reviewResult.llmStatus());
            task.setFinishedAt(finishedAt);
            task.setDurationSeconds((int) Duration.between(startedAt, finishedAt).toSeconds());
            reviewTaskMapper.updateById(task);
            appendTimeline(task.getId(), "Review completed", finishedAt, "DONE", 5);
        } catch (RuntimeException ex) {
            LocalDateTime failedAt = LocalDateTime.now();
            task.setStatus("FAILED");
            task.setRiskLevel("HIGH");
            task.setLlmStatus("FAILED");
            task.setFinishedAt(failedAt);
            task.setDurationSeconds((int) Duration.between(startedAt, failedAt).toSeconds());
            reviewTaskMapper.updateById(task);
            appendTimeline(task.getId(), failureLabel(ex), failedAt, "FAILED", 5);
        }
    }

    private void replaceChangedFiles(Long taskId, GithubPullRequestDiff diff) {
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
}
