package com.repoguard.agent.github.comment;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.GithubCommentPublicationBatchDto;
import com.repoguard.agent.dto.GithubCommentPublicationHistoryResponse;
import com.repoguard.agent.entity.GithubCommentPublicationBatch;
import com.repoguard.agent.entity.GithubCommentPublicationBatchItem;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.GithubCommentPublicationBatchItemMapper;
import com.repoguard.agent.mapper.GithubCommentPublicationBatchMapper;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.service.GithubCommentHistoryQueryService;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class GithubCommentHistoryQueryServiceImpl implements GithubCommentHistoryQueryService {

    private final ReviewTaskMapper reviewTaskMapper;
    private final GithubCommentPublicationBatchMapper batchMapper;
    private final GithubCommentPublicationBatchItemMapper batchItemMapper;
    private final GithubCommentPublicationHistoryAssembler historyAssembler;

    public GithubCommentHistoryQueryServiceImpl(
        ReviewTaskMapper reviewTaskMapper,
        GithubCommentPublicationBatchMapper batchMapper,
        GithubCommentPublicationBatchItemMapper batchItemMapper,
        GithubCommentPublicationHistoryAssembler historyAssembler
    ) {
        this.reviewTaskMapper = reviewTaskMapper;
        this.batchMapper = batchMapper;
        this.batchItemMapper = batchItemMapper;
        this.historyAssembler = historyAssembler;
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
            .map(batch -> historyAssembler.assembleBatch(batch, itemsByBatchId.getOrDefault(batch.getId(), List.of())))
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
}
