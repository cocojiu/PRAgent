package com.repoguard.agent.github.comment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.entity.GithubCommentPublicationBatch;
import com.repoguard.agent.entity.GithubCommentPublicationBatchItem;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.github.GithubWritebackFailureClassifier;
import com.repoguard.agent.mapper.GithubCommentPublicationBatchItemMapper;
import com.repoguard.agent.mapper.GithubCommentPublicationBatchMapper;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class GithubCommentHistoryQueryServiceImplTest {

    private final ReviewTaskMapper reviewTaskMapper = org.mockito.Mockito.mock(ReviewTaskMapper.class);
    private final GithubCommentPublicationBatchMapper batchMapper =
        org.mockito.Mockito.mock(GithubCommentPublicationBatchMapper.class);
    private final GithubCommentPublicationBatchItemMapper batchItemMapper =
        org.mockito.Mockito.mock(GithubCommentPublicationBatchItemMapper.class);
    private final GithubCommentHistoryQueryServiceImpl service = new GithubCommentHistoryQueryServiceImpl(
        reviewTaskMapper,
        batchMapper,
        batchItemMapper,
        new GithubCommentPublicationHistoryAssembler(new GithubWritebackFailureClassifier())
    );

    @Test
    void returnsBatchesAndItemsWithNormalizedStatus() {
        when(reviewTaskMapper.selectById(521L)).thenReturn(task());
        Page<GithubCommentPublicationBatch> page = Page.of(1, 20);
        page.setRecords(List.of(batch(10L, "completed", 1, 0)));
        page.setTotal(1);
        when(batchMapper.selectPage(any(), any())).thenReturn(page);
        when(batchItemMapper.selectList(any())).thenReturn(List.of(item(10L, true, "published", "GitHub comment published")));

        var result = service.getPublicationHistory(521L, 1, 20, " COMPLETED ");

        assertThat(result.taskId()).isEqualTo(521L);
        assertThat(result.total()).isEqualTo(1);
        assertThat(result.status()).isEqualTo("completed");
        assertThat(result.batches()).hasSize(1);
        assertThat(result.batches().getFirst().batchId()).isEqualTo(10L);
        assertThat(result.batches().getFirst().createdAt()).isEqualTo("2026-06-09 12:00:00");
        assertThat(result.batches().getFirst().items()).hasSize(1);
        assertThat(result.batches().getFirst().items().getFirst().url()).isEqualTo("https://github.com/comment/1");
    }

    @Test
    void addsReadableFailureDetailsToFailedItems() {
        when(reviewTaskMapper.selectById(521L)).thenReturn(task());
        Page<GithubCommentPublicationBatch> page = Page.of(1, 20);
        page.setRecords(List.of(batch(11L, "failed", 0, 1)));
        page.setTotal(1);
        when(batchMapper.selectPage(any(), any())).thenReturn(page);
        when(batchItemMapper.selectList(any())).thenReturn(List.of(item(11L, false, "failed", "401 Bad credentials")));

        var result = service.getPublicationHistory(521L, 1, 20, null);

        var historyItem = result.batches().getFirst().items().getFirst();
        assertThat(historyItem.failureCategory()).isEqualTo("github_token_invalid");
        assertThat(historyItem.failureReason()).isEqualTo("GitHub Token 无效或已过期");
        assertThat(historyItem.failureSuggestion()).contains("更新 GitHub Token");
    }

    @Test
    void returnsEmptyPageWithoutLoadingBatchItems() {
        when(reviewTaskMapper.selectById(521L)).thenReturn(task());
        Page<GithubCommentPublicationBatch> page = Page.of(2, 20);
        page.setRecords(List.of());
        page.setTotal(0);
        when(batchMapper.selectPage(any(), any())).thenReturn(page);

        var result = service.getPublicationHistory(521L, 2, 20, " ");

        assertThat(result.page()).isEqualTo(2);
        assertThat(result.status()).isNull();
        assertThat(result.batches()).isEmpty();
        verifyNoInteractions(batchItemMapper);
    }

    @Test
    void rejectsUnknownReviewTask() {
        when(reviewTaskMapper.selectById(999L)).thenReturn(null);

        assertThatThrownBy(() -> service.getPublicationHistory(999L, 1, 20, null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Review task not found: 999");
        verifyNoInteractions(batchMapper, batchItemMapper);
    }

    @Test
    void summariesCountItemsWithoutLoadingTheirEntities() {
        when(reviewTaskMapper.selectById(521L)).thenReturn(task());
        Page<GithubCommentPublicationBatch> page = Page.of(1, 20);
        page.setRecords(List.of(batch(10L, "completed", 5000, 0), batch(11L, "empty", 0, 0)));
        page.setTotal(2);
        when(batchMapper.selectPage(any(), any())).thenReturn(page);
        when(batchItemMapper.selectHistoryItemCounts(521L, List.of(10L, 11L))).thenReturn(List.of(Map.of("batchId", 10L, "itemsTotal", 5000L)));

        var result = service.getPublicationBatches(521L, 1, 20, null);

        assertThat(result.batches().getFirst().items()).isEmpty();
        assertThat(result.batches().getFirst().itemsTotal()).isEqualTo(5000);
        assertThat(result.batches().getFirst().hasMore()).isTrue();
        assertThat(result.batches().getLast().itemsTotal()).isZero();
        assertThat(result.batches().getLast().hasMore()).isFalse();
        verify(batchItemMapper, never()).selectList(any());
    }

    @Test
    void detailPageUsesOneExtraRowAndReturnsAnAdvancingCursor() {
        when(reviewTaskMapper.selectById(521L)).thenReturn(task());
        when(batchMapper.selectOne(any())).thenReturn(batch(10L, "completed", 45, 0));
        when(batchItemMapper.countHistoryItems(521L, 10L)).thenReturn(45L);
        when(batchItemMapper.selectHistoryItemsAfterId(521L, 10L, 20, 21)).thenReturn(IntStream.rangeClosed(21, 41).mapToObj(id -> {
            var item = item(10L, true, "published", "published");
            item.setId((long) id);
            item.setFindingId((long) id);
            return item;
        }).toList());

        var result = service.getPublicationItems(521L, 10L, 20, 20);

        assertThat(result.total()).isEqualTo(45);
        assertThat(result.items()).hasSize(20);
        assertThat(result.items().getFirst().findingId()).isEqualTo(21L);
        assertThat(result.items().getLast().findingId()).isEqualTo(40L);
        assertThat(result.nextAfterId()).isEqualTo(40L);
        assertThat(result.hasMore()).isTrue();
    }

    @Test
    void returnsEmptyDetailPageAtTheEndWithoutInventingACursor() {
        when(reviewTaskMapper.selectById(521L)).thenReturn(task());
        when(batchMapper.selectOne(any())).thenReturn(batch(10L, "completed", 0, 0));
        when(batchItemMapper.countHistoryItems(521L, 10L)).thenReturn(0L);
        when(batchItemMapper.selectHistoryItemsAfterId(521L, 10L, 0, 21)).thenReturn(List.of());

        var result = service.getPublicationItems(521L, 10L, 0, 20);

        assertThat(result.total()).isZero();
        assertThat(result.items()).isEmpty();
        assertThat(result.nextAfterId()).isNull();
        assertThat(result.hasMore()).isFalse();
    }

    @Test
    void rejectsABatchOutsideTheTaskBeforeReadingDetails() {
        when(reviewTaskMapper.selectById(521L)).thenReturn(task());
        when(batchMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.getPublicationItems(521L, 999L, 0, 20))
            .isInstanceOf(BusinessException.class).hasMessageContaining("batch not found");
        verifyNoInteractions(batchItemMapper);
    }

    @Test
    void rejectsUnboundedPagesAndNegativeCursors() {
        when(reviewTaskMapper.selectById(521L)).thenReturn(task());
        assertThatThrownBy(() -> service.getPublicationBatches(521L, 1, 101, null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.getPublicationBatches(521L, 0, 20, null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.getPublicationItems(521L, 10L, -1, 20)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.getPublicationItems(521L, 10L, 0, 0)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(batchMapper, batchItemMapper);
    }

    private ReviewTask task() {
        ReviewTask task = new ReviewTask();
        task.setId(521L);
        return task;
    }

    private GithubCommentPublicationBatch batch(Long id, String status, int succeededCount, int failedCount) {
        GithubCommentPublicationBatch batch = new GithubCommentPublicationBatch();
        batch.setId(id);
        batch.setTaskId(521L);
        batch.setStatus(status);
        batch.setTotalFindings(1);
        batch.setAttemptedCount(1);
        batch.setSucceededCount(succeededCount);
        batch.setFailedCount(failedCount);
        batch.setSkippedCount(0);
        batch.setCreatedAt(LocalDateTime.of(2026, 6, 9, 12, 0));
        batch.setCompletedAt(LocalDateTime.of(2026, 6, 9, 12, 0, 1));
        return batch;
    }

    private GithubCommentPublicationBatchItem item(Long batchId, boolean success, String status, String message) {
        GithubCommentPublicationBatchItem item = new GithubCommentPublicationBatchItem();
        item.setId(1L);
        item.setBatchId(batchId);
        item.setTaskId(521L);
        item.setFindingId(1L);
        item.setFilePath("README.md");
        item.setLineNumber(2);
        item.setTargetType("line");
        item.setSuccess(success);
        item.setStatus(status);
        item.setMessage(message);
        item.setGithubUrl(success ? "https://github.com/comment/1" : null);
        item.setGithubCommentId(success ? 101L : null);
        item.setPublishedAt(success ? LocalDateTime.of(2026, 6, 9, 12, 0, 1) : null);
        return item;
    }
}
