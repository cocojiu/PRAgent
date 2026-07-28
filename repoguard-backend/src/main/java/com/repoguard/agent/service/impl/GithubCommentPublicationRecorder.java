package com.repoguard.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.repoguard.agent.dto.GithubCommentPublishItem;
import com.repoguard.agent.dto.GithubCommentPublishResponse;
import com.repoguard.agent.entity.GithubCommentPublication;
import com.repoguard.agent.entity.GithubCommentPublicationBatch;
import com.repoguard.agent.entity.GithubCommentPublicationBatchItem;
import com.repoguard.agent.github.GithubCommentPublicationBatchStatus;
import com.repoguard.agent.github.GithubReviewCommentResult;
import com.repoguard.agent.mapper.GithubCommentPublicationBatchItemMapper;
import com.repoguard.agent.mapper.GithubCommentPublicationBatchMapper;
import com.repoguard.agent.mapper.GithubCommentPublicationMapper;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Persists GitHub comment publication records and batch history.
 */
@Component
public class GithubCommentPublicationRecorder {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int HISTORY_BATCH_SIZE = 500;

    private final GithubCommentPublicationMapper githubCommentPublicationMapper;
    private final GithubCommentPublicationBatchMapper githubCommentPublicationBatchMapper;
    private final GithubCommentPublicationBatchItemMapper githubCommentPublicationBatchItemMapper;

    public GithubCommentPublicationRecorder(
        GithubCommentPublicationMapper githubCommentPublicationMapper,
        GithubCommentPublicationBatchMapper githubCommentPublicationBatchMapper,
        GithubCommentPublicationBatchItemMapper githubCommentPublicationBatchItemMapper
    ) {
        this.githubCommentPublicationMapper = githubCommentPublicationMapper;
        this.githubCommentPublicationBatchMapper = githubCommentPublicationBatchMapper;
        this.githubCommentPublicationBatchItemMapper = githubCommentPublicationBatchItemMapper;
    }

    public GithubCommentPublication recordPublication(Long taskId, GithubReviewCommentResult result) {
        LocalDateTime now = LocalDateTime.now();
        GithubCommentPublication publication = result.findingId() == null
            ? loadPrSummaryPublication(taskId)
            : githubCommentPublicationMapper.selectOne(
                new LambdaQueryWrapper<GithubCommentPublication>()
                    .eq(GithubCommentPublication::getFindingId, result.findingId())
                    .last("limit 1")
            );
        boolean existing = publication != null;
        if (!existing) {
            publication = new GithubCommentPublication();
            publication.setTaskId(taskId);
            publication.setFindingId(result.findingId());
            publication.setCreatedAt(now);
        }
        boolean alreadyPublished = Boolean.TRUE.equals(publication.getSuccess())
            && StringUtils.hasText(publication.getGithubUrl());
        boolean currentSucceeded = Boolean.TRUE.equals(result.success());
        publication.setTargetType(result.targetType());
        if (!alreadyPublished || currentSucceeded) {
            publication.setStatus(result.status());
            publication.setSuccess(result.success());
            publication.setGithubCommentId(result.commentId());
            publication.setGithubUrl(result.url());
            publication.setPublishedAt(currentSucceeded ? now : null);
        }
        publication.setMessage(result.message());
        publication.setUpdatedAt(now);
        if (existing) {
            githubCommentPublicationMapper.updateById(publication);
        } else {
            githubCommentPublicationMapper.insert(publication);
        }
        return publication;
    }

    public Long recordBatch(GithubCommentPublishResponse response) {
        Long batchId = createBatch(response.taskId(), response.totalFindings());
        completeBatch(batchId, response);
        return batchId;
    }

    public Long createBatch(Long taskId, Integer totalFindings) {
        LocalDateTime now = LocalDateTime.now();
        GithubCommentPublicationBatch batch = new GithubCommentPublicationBatch();
        batch.setTaskId(taskId);
        batch.setStatus(GithubCommentPublicationBatchStatus.QUEUED.code());
        batch.setTotalFindings(safe(totalFindings));
        batch.setAttemptedCount(0);
        batch.setSucceededCount(0);
        batch.setFailedCount(0);
        batch.setSkippedCount(0);
        batch.setCreatedAt(now);
        batch.setCompletedAt(null);
        batch.setClaimedAt(null);
        batch.setClaimedBy(null);
        batch.setNextRetryAt(null);
        batch.setLastError(null);
        githubCommentPublicationBatchMapper.insert(batch);
        return batch.getId();
    }

    public void markBatchRunning(Long batchId) {
        LocalDateTime now = LocalDateTime.now();
        tryMarkBatchRunning(batchId, null, now, now);
    }

    public boolean tryMarkBatchRunning(
        Long batchId,
        String workerId,
        LocalDateTime claimedAt,
        LocalDateTime runningExpiredBefore
    ) {
        int updated = githubCommentPublicationBatchMapper.update(
            new UpdateWrapper<GithubCommentPublicationBatch>()
                .eq("id", batchId)
                .isNull("completed_at")
                .and(status -> status
                    .eq("status", GithubCommentPublicationBatchStatus.QUEUED.code())
                    .or(running -> running
                        .eq("status", GithubCommentPublicationBatchStatus.RUNNING.code())
                        .and(stale -> stale
                            .isNull("claimed_at")
                            .or()
                            .le("claimed_at", runningExpiredBefore)
                        )
                    )
                )
                .set("status", GithubCommentPublicationBatchStatus.RUNNING.code())
                .set("claimed_at", claimedAt)
                .set("claimed_by", workerId)
                .set("next_retry_at", null)
                .set("last_error", null)
        );
        return updated > 0;
    }

    public void markBatchQueuedForRetry(Long batchId, LocalDateTime nextRetryAt, String error) {
        githubCommentPublicationBatchMapper.update(
            new UpdateWrapper<GithubCommentPublicationBatch>()
                .eq("id", batchId)
                .isNull("completed_at")
                .in(
                    "status",
                    GithubCommentPublicationBatchStatus.QUEUED.code(),
                    GithubCommentPublicationBatchStatus.RUNNING.code()
                )
                .set("status", GithubCommentPublicationBatchStatus.QUEUED.code())
                .set("claimed_at", null)
                .set("claimed_by", null)
                .set("next_retry_at", nextRetryAt)
                .set("last_error", truncate(error))
        );
    }

    public List<GithubCommentPublicationBatch> findRecoverableBatches(
        LocalDateTime now,
        LocalDateTime runningExpiredBefore,
        int batchSize
    ) {
        return githubCommentPublicationBatchMapper.selectList(
            new QueryWrapper<GithubCommentPublicationBatch>()
                .isNull("completed_at")
                .and(recoverable -> recoverable
                    .eq("status", GithubCommentPublicationBatchStatus.QUEUED.code())
                    .and(ready -> ready
                        .isNull("next_retry_at")
                        .or()
                        .le("next_retry_at", now)
                    )
                    .or(staleRunning -> staleRunning
                        .eq("status", GithubCommentPublicationBatchStatus.RUNNING.code())
                        .and(stale -> stale
                            .isNull("claimed_at")
                            .or()
                            .le("claimed_at", runningExpiredBefore)
                        )
                    )
                )
                .orderByAsc("created_at")
                .orderByAsc("id")
                .last("limit " + Math.max(1, batchSize))
        );
    }

    public void completeBatch(Long batchId, GithubCommentPublishResponse response) {
        LocalDateTime now = LocalDateTime.now();
        int updated = githubCommentPublicationBatchMapper.update(
            new UpdateWrapper<GithubCommentPublicationBatch>()
                .eq("id", batchId)
                .isNull("completed_at")
                .set("status", resolvePublicationBatchStatus(response))
                .set("total_findings", safe(response.totalFindings()))
                .set("attempted_count", safe(response.attemptedCount()))
                .set("succeeded_count", safe(response.succeededCount()))
                .set("failed_count", safe(response.failedCount()))
                .set("skipped_count", safe(response.skippedCount()))
                .set("completed_at", now)
                .set("claimed_at", null)
                .set("claimed_by", null)
                .set("next_retry_at", null)
                .set("last_error", null)
        );
        if (updated <= 0) {
            return;
        }
        List<GithubCommentPublicationBatchItem> historyItems = response.items().stream()
            .map(item -> historyItem(batchId, response.taskId(), item, now))
            .toList();
        for (int from = 0; from < historyItems.size(); from += HISTORY_BATCH_SIZE) {
            githubCommentPublicationBatchItemMapper.insertBatch(
                historyItems.subList(from, Math.min(from + HISTORY_BATCH_SIZE, historyItems.size()))
            );
        }
    }

    public void failBatch(Long batchId, Integer totalFindings) {
        failBatch(batchId, totalFindings, null);
    }

    public void failBatch(Long batchId, Integer totalFindings, String error) {
        githubCommentPublicationBatchMapper.update(
            new UpdateWrapper<GithubCommentPublicationBatch>()
                .eq("id", batchId)
                .isNull("completed_at")
                .set("status", GithubCommentPublicationBatchStatus.FAILED.code())
                .set("total_findings", safe(totalFindings))
                .set("attempted_count", 0)
                .set("succeeded_count", 0)
                .set("failed_count", 1)
                .set("skipped_count", 0)
                .set("completed_at", LocalDateTime.now())
                .set("claimed_at", null)
                .set("claimed_by", null)
                .set("next_retry_at", null)
                .set("last_error", truncate(error))
        );
    }

    private String resolvePublicationBatchStatus(GithubCommentPublishResponse response) {
        if (response.attemptedCount() == 0 && response.skippedCount() == 0) {
            return GithubCommentPublicationBatchStatus.EMPTY.code();
        }
        if (response.failedCount() > 0) {
            return response.succeededCount() > 0
                ? GithubCommentPublicationBatchStatus.PARTIAL_FAILED.code()
                : GithubCommentPublicationBatchStatus.FAILED.code();
        }
        if (response.attemptedCount() == 0 && response.skippedCount() > 0) {
            return GithubCommentPublicationBatchStatus.SKIPPED.code();
        }
        return GithubCommentPublicationBatchStatus.COMPLETED.code();
    }

    private GithubCommentPublication loadPrSummaryPublication(Long taskId) {
        return githubCommentPublicationMapper.selectOne(
            new LambdaQueryWrapper<GithubCommentPublication>()
                .eq(GithubCommentPublication::getTaskId, taskId)
                .isNull(GithubCommentPublication::getFindingId)
                .eq(GithubCommentPublication::getTargetType, "pull_request")
                .last("limit 1")
        );
    }

    private LocalDateTime parseDateTimeOrNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return LocalDateTime.parse(value, DATE_TIME_FORMATTER);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private GithubCommentPublicationBatchItem historyItem(
        Long batchId,
        Long taskId,
        GithubCommentPublishItem item,
        LocalDateTime createdAt
    ) {
        GithubCommentPublicationBatchItem historyItem = new GithubCommentPublicationBatchItem();
        historyItem.setBatchId(batchId);
        historyItem.setTaskId(taskId);
        historyItem.setFindingId(item.findingId());
        historyItem.setFilePath(item.file());
        historyItem.setLineNumber(item.line());
        historyItem.setTargetType(item.targetType());
        historyItem.setStatus(item.status());
        historyItem.setSuccess(item.success());
        historyItem.setGithubCommentId(item.githubCommentId());
        historyItem.setGithubUrl(item.url());
        historyItem.setMessage(item.message());
        historyItem.setPublishedAt(parseDateTimeOrNull(item.publishedAt()));
        historyItem.setCreatedAt(createdAt);
        return historyItem;
    }

    private int safe(Integer value) {
        return value == null ? 0 : value;
    }

    private String truncate(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.length() > 1024 ? value.substring(0, 1024) : value;
    }
}
