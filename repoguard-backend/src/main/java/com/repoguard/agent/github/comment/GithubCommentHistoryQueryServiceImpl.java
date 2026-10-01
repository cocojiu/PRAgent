package com.repoguard.agent.github.comment;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.GithubCommentPublicationBatchDto;
import com.repoguard.agent.dto.GithubCommentPublicationHistoryResponse;
import com.repoguard.agent.dto.GithubCommentPublicationItemsResponse;
import com.repoguard.agent.entity.GithubCommentPublicationBatch;
import com.repoguard.agent.entity.GithubCommentPublicationBatchItem;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.GithubCommentPublicationBatchItemMapper;
import com.repoguard.agent.mapper.GithubCommentPublicationBatchMapper;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.service.GithubCommentHistoryQueryService;
import java.util.List;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
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
        ReviewTask task = requireTask(taskId);
        String normalizedStatus = normalizeOptionalStatus(status);
        Page<GithubCommentPublicationBatch> batchPage = loadBatchPage(taskId, page, pageSize, normalizedStatus);
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

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public GithubCommentPublicationHistoryResponse getPublicationBatches(Long taskId, int page, int pageSize, String status) {
        requireTask(taskId);
        String normalizedStatus = normalizeOptionalStatus(status);
        Page<GithubCommentPublicationBatch> result = loadBatchPage(taskId, page, pageSize, normalizedStatus);
        List<GithubCommentPublicationBatch> batches = result.getRecords();
        Map<Long, Long> totals = new HashMap<>();
        if (!batches.isEmpty()) {
            List<Map<String, Object>> counts = batchItemMapper.selectHistoryItemCounts(taskId,
                batches.stream().map(GithubCommentPublicationBatch::getId).toList());
            counts.forEach(row -> totals.put(((Number) row.get("batchId")).longValue(), ((Number) row.get("itemsTotal")).longValue()));
        }
        return new GithubCommentPublicationHistoryResponse(taskId, result.getTotal(), page, pageSize, normalizedStatus,
            batches.stream().map(batch -> historyAssembler.assembleBatch(batch, List.of(), totals.getOrDefault(batch.getId(), 0L))).toList());
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public GithubCommentPublicationItemsResponse getPublicationItems(Long taskId, Long batchId, long afterId, int pageSize) {
        requireTask(taskId);
        validatePage(1, pageSize);
        if (afterId < 0) throw new BusinessException(ErrorCode.BAD_REQUEST, "afterId must be nonnegative");
        GithubCommentPublicationBatch batch = batchMapper.selectOne(new LambdaQueryWrapper<GithubCommentPublicationBatch>()
            .select(GithubCommentPublicationBatch::getId, GithubCommentPublicationBatch::getTaskId)
            .eq(GithubCommentPublicationBatch::getId, batchId).eq(GithubCommentPublicationBatch::getTaskId, taskId));
        if (batch == null) throw new BusinessException(ErrorCode.TASK_NOT_FOUND, "Comment publication batch not found");
        long total = batchItemMapper.countHistoryItems(taskId, batchId);
        List<GithubCommentPublicationBatchItem> items = batchItemMapper.selectHistoryItemsAfterId(taskId, batchId, afterId, pageSize + 1);
        boolean hasMore = items.size() > pageSize;
        List<GithubCommentPublicationBatchItem> page = hasMore ? items.subList(0, pageSize) : items;
        Long nextAfterId = page.isEmpty() ? null : page.getLast().getId();
        return new GithubCommentPublicationItemsResponse(taskId, batchId, total, pageSize, afterId, nextAfterId, hasMore,
            page.stream().map(historyAssembler::assembleItem).toList());
    }

    private Page<GithubCommentPublicationBatch> loadBatchPage(Long taskId, int page, int pageSize, String status) {
        validatePage(page, pageSize);
        return batchMapper.selectPage(Page.of(page, pageSize), new LambdaQueryWrapper<GithubCommentPublicationBatch>()
            .eq(GithubCommentPublicationBatch::getTaskId, taskId)
            .eq(status != null, GithubCommentPublicationBatch::getStatus, status)
            .orderByDesc(GithubCommentPublicationBatch::getCreatedAt).orderByDesc(GithubCommentPublicationBatch::getId));
    }

    private void validatePage(int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 100) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Invalid publication history page");
        }
    }

    private ReviewTask requireTask(Long taskId) {
        ReviewTask task = reviewTaskMapper.selectById(taskId);
        if (task == null) throw new BusinessException(ErrorCode.TASK_NOT_FOUND, "Review task not found: " + taskId);
        return task;
    }

    private String normalizeOptionalStatus(String status) {
        return StringUtils.hasText(status) ? status.trim().toLowerCase(Locale.ROOT) : null;
    }
}
