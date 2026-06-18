package com.repoguard.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.GithubCommentPublicationBatchDto;
import com.repoguard.agent.dto.GithubCommentPublicationHistoryItem;
import com.repoguard.agent.dto.GithubCommentPublicationHistoryResponse;
import com.repoguard.agent.entity.GithubCommentPublicationBatch;
import com.repoguard.agent.entity.GithubCommentPublicationBatchItem;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.github.GithubWritebackFailureClassifier;
import com.repoguard.agent.github.GithubWritebackFailureClassifier.FailureSummary;
import com.repoguard.agent.mapper.GithubCommentPublicationBatchItemMapper;
import com.repoguard.agent.mapper.GithubCommentPublicationBatchMapper;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.service.GithubCommentHistoryQueryService;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class GithubCommentHistoryQueryServiceImpl implements GithubCommentHistoryQueryService {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ReviewTaskMapper reviewTaskMapper;
    private final GithubCommentPublicationBatchMapper batchMapper;
    private final GithubCommentPublicationBatchItemMapper batchItemMapper;
    private final GithubWritebackFailureClassifier failureClassifier;

    public GithubCommentHistoryQueryServiceImpl(
        ReviewTaskMapper reviewTaskMapper,
        GithubCommentPublicationBatchMapper batchMapper,
        GithubCommentPublicationBatchItemMapper batchItemMapper,
        GithubWritebackFailureClassifier failureClassifier
    ) {
        this.reviewTaskMapper = reviewTaskMapper;
        this.batchMapper = batchMapper;
        this.batchItemMapper = batchItemMapper;
        this.failureClassifier = failureClassifier;
    }

    @Override
    public GithubCommentPublicationHistoryResponse getPublicationHistory(
        Long taskId,
        int page,
        int pageSize,
        String status
    ) {
        ReviewTask task = reviewTaskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND, "Review task not found: " + taskId);
        }

        String normalizedStatus = normalizeOptionalStatus(status);
        LambdaQueryWrapper<GithubCommentPublicationBatch> batchQuery =
            new LambdaQueryWrapper<GithubCommentPublicationBatch>()
                .eq(GithubCommentPublicationBatch::getTaskId, taskId)
                .eq(normalizedStatus != null, GithubCommentPublicationBatch::getStatus, normalizedStatus)
                .orderByDesc(GithubCommentPublicationBatch::getCreatedAt)
                .orderByDesc(GithubCommentPublicationBatch::getId);
        Page<GithubCommentPublicationBatch> batchPage = batchMapper.selectPage(
            Page.of(page, pageSize),
            batchQuery
        );
        List<GithubCommentPublicationBatch> batches = batchPage.getRecords();
        if (batches == null || batches.isEmpty()) {
            return new GithubCommentPublicationHistoryResponse(
                task.getId(),
                batchPage.getTotal(),
                page,
                pageSize,
                normalizedStatus,
                List.of()
            );
        }

        List<Long> batchIds = batches.stream().map(GithubCommentPublicationBatch::getId).toList();
        List<GithubCommentPublicationBatchItem> batchItems = batchItemMapper.selectList(
            new LambdaQueryWrapper<GithubCommentPublicationBatchItem>()
                .in(GithubCommentPublicationBatchItem::getBatchId, batchIds)
                .orderByAsc(GithubCommentPublicationBatchItem::getId)
        );
        Map<Long, List<GithubCommentPublicationBatchItem>> itemsByBatchId =
            (batchItems == null ? List.<GithubCommentPublicationBatchItem>of() : batchItems)
                .stream()
                .collect(Collectors.groupingBy(GithubCommentPublicationBatchItem::getBatchId));

        List<GithubCommentPublicationBatchDto> batchDtos = batches.stream()
            .map(batch -> toBatchDto(batch, itemsByBatchId.getOrDefault(batch.getId(), List.of())))
            .toList();
        return new GithubCommentPublicationHistoryResponse(
            task.getId(),
            batchPage.getTotal(),
            page,
            pageSize,
            normalizedStatus,
            batchDtos
        );
    }

    private String normalizeOptionalStatus(String status) {
        return StringUtils.hasText(status) ? status.trim().toLowerCase(Locale.ROOT) : null;
    }

    private GithubCommentPublicationBatchDto toBatchDto(
        GithubCommentPublicationBatch batch,
        List<GithubCommentPublicationBatchItem> items
    ) {
        return new GithubCommentPublicationBatchDto(
            batch.getId(),
            batch.getStatus(),
            batch.getTotalFindings(),
            batch.getAttemptedCount(),
            batch.getSucceededCount(),
            batch.getFailedCount(),
            batch.getSkippedCount(),
            format(batch.getCreatedAt()),
            format(batch.getCompletedAt()),
            items.stream().map(this::toHistoryItem).toList()
        );
    }

    private GithubCommentPublicationHistoryItem toHistoryItem(GithubCommentPublicationBatchItem item) {
        FailureSummary failureSummary = failureClassifier.classify(
            item.getStatus(),
            item.getSuccess(),
            item.getMessage()
        );
        return new GithubCommentPublicationHistoryItem(
            item.getFindingId(),
            item.getFilePath(),
            item.getLineNumber(),
            item.getTargetType(),
            item.getSuccess(),
            item.getStatus(),
            item.getMessage(),
            failureSummary.category(),
            failureSummary.reason(),
            failureSummary.suggestion(),
            item.getGithubUrl(),
            item.getGithubCommentId(),
            format(item.getPublishedAt())
        );
    }

    private String format(LocalDateTime value) {
        return value == null ? null : value.format(DATE_TIME_FORMATTER);
    }
}
