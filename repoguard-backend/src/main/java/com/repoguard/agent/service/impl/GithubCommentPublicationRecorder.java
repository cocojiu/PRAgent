package com.repoguard.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Persists GitHub comment publication records and batch history.
 */
@Component
public class GithubCommentPublicationRecorder {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

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
        LocalDateTime now = LocalDateTime.now();
        GithubCommentPublicationBatch batch = new GithubCommentPublicationBatch();
        batch.setTaskId(response.taskId());
        batch.setStatus(resolvePublicationBatchStatus(response));
        batch.setTotalFindings(response.totalFindings());
        batch.setAttemptedCount(response.attemptedCount());
        batch.setSucceededCount(response.succeededCount());
        batch.setFailedCount(response.failedCount());
        batch.setSkippedCount(response.skippedCount());
        batch.setCreatedAt(now);
        batch.setCompletedAt(now);
        githubCommentPublicationBatchMapper.insert(batch);

        for (GithubCommentPublishItem item : response.items()) {
            GithubCommentPublicationBatchItem historyItem = new GithubCommentPublicationBatchItem();
            historyItem.setBatchId(batch.getId());
            historyItem.setTaskId(response.taskId());
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
            historyItem.setCreatedAt(now);
            githubCommentPublicationBatchItemMapper.insert(historyItem);
        }
        return batch.getId();
    }

    private String resolvePublicationBatchStatus(GithubCommentPublishResponse response) {
        if (response.totalFindings() == 0) {
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
}
