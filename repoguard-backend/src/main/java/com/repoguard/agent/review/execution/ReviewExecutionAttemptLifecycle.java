package com.repoguard.agent.review.execution;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.repoguard.agent.entity.ReviewExecutionAttempt;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.ChangedFileMapper;
import com.repoguard.agent.mapper.ReviewExecutionAttemptMapper;
import com.repoguard.agent.mapper.ReviewFindingMapper;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.observability.RepoGuardMetrics;
import com.repoguard.agent.review.ReviewExecutionProvenance;
import com.repoguard.agent.review.ReviewResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ReviewExecutionAttemptLifecycle {

    public static final String RUNNING = "RUNNING";
    public static final String COMPLETED = "COMPLETED";
    public static final String PARTIAL = "PARTIAL";
    public static final String FAILED = "FAILED";
    public static final String SUPERSEDED = "SUPERSEDED";
    public static final String ABANDONED = "ABANDONED";

    private final ReviewExecutionAttemptMapper attemptMapper;
    private final ReviewTaskMapper taskMapper;
    private final ReviewFindingMapper findingMapper;
    private final ChangedFileMapper changedFileMapper;
    private final RepoGuardMetrics metrics;

    @Autowired
    public ReviewExecutionAttemptLifecycle(
        ReviewExecutionAttemptMapper attemptMapper,
        ReviewTaskMapper taskMapper,
        ReviewFindingMapper findingMapper,
        ChangedFileMapper changedFileMapper,
        RepoGuardMetrics metrics
    ) {
        this.attemptMapper = Objects.requireNonNull(attemptMapper, "attemptMapper");
        this.taskMapper = Objects.requireNonNull(taskMapper, "taskMapper");
        this.findingMapper = Objects.requireNonNull(findingMapper, "findingMapper");
        this.changedFileMapper = Objects.requireNonNull(changedFileMapper, "changedFileMapper");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    public ReviewExecutionAttemptLifecycle(
        ReviewExecutionAttemptMapper attemptMapper,
        ReviewTaskMapper taskMapper,
        ReviewFindingMapper findingMapper,
        ChangedFileMapper changedFileMapper
    ) {
        this.attemptMapper = Objects.requireNonNull(attemptMapper, "attemptMapper");
        this.taskMapper = Objects.requireNonNull(taskMapper, "taskMapper");
        this.findingMapper = Objects.requireNonNull(findingMapper, "findingMapper");
        this.changedFileMapper = Objects.requireNonNull(changedFileMapper, "changedFileMapper");
        this.metrics = null;
    }

    public ReviewExecutionAttempt start(
        ReviewTask task,
        String claimId,
        String workerId,
        LocalDateTime startedAt
    ) {
        Objects.requireNonNull(task, "task");
        int attemptNo = attemptMapper.selectLatestAttemptNo(task.getId()) + 1;
        ReviewExecutionAttempt attempt = new ReviewExecutionAttempt();
        attempt.setTaskId(task.getId());
        attempt.setAttemptNo(attemptNo);
        attempt.setGeneration(task.getGeneration() == null ? 1L : task.getGeneration());
        attempt.setCommitSha(task.getCommitSha());
        attempt.setInputFingerprint(inputFingerprint(task));
        attempt.setClaimId(claimId);
        attempt.setWorkerId(workerId);
        attempt.setStatus(RUNNING);
        attempt.setDiffFetchMs(0L);
        attempt.setReviewMs(0L);
        attempt.setPersistMs(0L);
        attempt.setTotalMs(0L);
        attempt.setQueuedAt(task.getCreatedAt() == null ? startedAt : task.getCreatedAt());
        attempt.setStartedAt(startedAt);
        attempt.setCreatedAt(startedAt);
        if (attemptMapper.insert(attempt) != 1 || attempt.getId() == null) {
            throw new IllegalStateException("Review execution Attempt could not be created");
        }
        if (taskMapper.attachCurrentAttempt(task.getId(), claimId, attempt.getId()) != 1) {
            throw new IllegalStateException("Review execution Attempt lost its task claim");
        }
        findingMapper.markCurrentAttemptHistorical(task.getId());
        changedFileMapper.markCurrentAttemptHistorical(task.getId());
        task.setCurrentAttemptId(attempt.getId());
        recordAttemptStatus(RUNNING);
        return attempt;
    }

    public void complete(
        ReviewExecutionAttempt attempt,
        ReviewResult result,
        ReviewAttemptStageDurations stages,
        LocalDateTime finishedAt,
        String attemptStatus,
        String failureCategory,
        String budgetExhaustedStage
    ) {
        Objects.requireNonNull(result, "result");
        ReviewExecutionProvenance provenance = result.executionProvenance();
        finish(
            attempt,
            stages,
            finishedAt,
            attemptStatus,
            failureCategory,
            budgetExhaustedStage,
            result,
            provenance
        );
    }

    public void fail(
        ReviewExecutionAttempt attempt,
        ReviewAttemptStageDurations stages,
        LocalDateTime finishedAt,
        String failureCategory
    ) {
        finish(attempt, stages, finishedAt, FAILED, failureCategory, null, null, null);
    }

    public void supersede(
        ReviewExecutionAttempt attempt,
        ReviewAttemptStageDurations stages,
        LocalDateTime finishedAt
    ) {
        finish(attempt, stages, finishedAt, SUPERSEDED, "HEAD_CHANGED", null, null, null);
    }

    public void refreshDurations(
        ReviewExecutionAttempt attempt,
        ReviewAttemptStageDurations stages
    ) {
        if (attempt == null || attempt.getId() == null || stages == null) {
            return;
        }
        attemptMapper.update(
            new LambdaUpdateWrapper<ReviewExecutionAttempt>()
                .eq(ReviewExecutionAttempt::getId, attempt.getId())
                .set(ReviewExecutionAttempt::getDiffFetchMs, stages.diffFetchMs())
                .set(ReviewExecutionAttempt::getReviewMs, stages.reviewMs())
                .set(ReviewExecutionAttempt::getPersistMs, stages.persistMs())
        );
    }

    private void finish(
        ReviewExecutionAttempt attempt,
        ReviewAttemptStageDurations stages,
        LocalDateTime finishedAt,
        String status,
        String failureCategory,
        String budgetExhaustedStage,
        ReviewResult result,
        ReviewExecutionProvenance provenance
    ) {
        Objects.requireNonNull(attempt, "attempt");
        ReviewAttemptStageDurations safeStages = stages == null ? new ReviewAttemptStageDurations() : stages;
        long totalMs = Math.max(0L, Duration.between(attempt.getStartedAt(), finishedAt).toMillis());
        LambdaUpdateWrapper<ReviewExecutionAttempt> update = new LambdaUpdateWrapper<ReviewExecutionAttempt>()
            .eq(ReviewExecutionAttempt::getId, attempt.getId())
            .eq(ReviewExecutionAttempt::getStatus, RUNNING)
            .set(ReviewExecutionAttempt::getStatus, status)
            .set(ReviewExecutionAttempt::getFailureCategory, failureCategory)
            .set(ReviewExecutionAttempt::getBudgetExhaustedStage, budgetExhaustedStage)
            .set(ReviewExecutionAttempt::getDiffFetchMs, safeStages.diffFetchMs())
            .set(ReviewExecutionAttempt::getReviewMs, safeStages.reviewMs())
            .set(ReviewExecutionAttempt::getPersistMs, safeStages.persistMs())
            .set(ReviewExecutionAttempt::getTotalMs, totalMs)
            .set(ReviewExecutionAttempt::getFinishedAt, finishedAt);
        if (result != null) {
            update
                .set(ReviewExecutionAttempt::getPromptTokens, result.llmPromptTokens())
                .set(ReviewExecutionAttempt::getCompletionTokens, result.llmCompletionTokens())
                .set(ReviewExecutionAttempt::getTotalTokens, result.llmTotalTokens())
                .set(ReviewExecutionAttempt::getEstimatedCost, result.llmEstimatedCost());
        }
        if (provenance != null) {
            update
                .set(ReviewExecutionAttempt::getPolicyVersion, provenance.strategyPolicyVersion())
                .set(ReviewExecutionAttempt::getPromptVersion, provenance.promptVersion())
                .set(ReviewExecutionAttempt::getContextVersion, provenance.contextVersion())
                .set(ReviewExecutionAttempt::getSchemaVersion, provenance.schemaVersion())
                .set(ReviewExecutionAttempt::getVerifierVersion, provenance.verifierVersion())
                .set(ReviewExecutionAttempt::getAggregationVersion, provenance.aggregationVersion());
        }
        if (attemptMapper.update(update) != 1) {
            throw new IllegalStateException("Review execution Attempt terminal update lost ownership");
        }
        attempt.setStatus(status);
        attempt.setFinishedAt(finishedAt);
        recordAttemptStatus(status);
    }

    private void recordAttemptStatus(String status) {
        if (metrics != null) {
            metrics.reviewExecutionAttempt(status);
        }
    }

    private String inputFingerprint(ReviewTask task) {
        String material = String.join(
            "|",
            safe(task.getOrganization()),
            safe(task.getRepository()),
            String.valueOf(task.getPrNumber()),
            safe(task.getCommitSha()),
            String.valueOf(task.getGeneration() == null ? 1L : task.getGeneration())
        );
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private String safe(Object value) {
        return value == null ? "" : value.toString().trim();
    }
}
