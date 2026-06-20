package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.dto.GithubCommentPublishItem;
import com.repoguard.agent.dto.GithubCommentPublishResponse;
import com.repoguard.agent.entity.GithubCommentPublication;
import com.repoguard.agent.entity.GithubCommentPublicationBatch;
import com.repoguard.agent.entity.GithubCommentPublicationBatchItem;
import com.repoguard.agent.github.GithubReviewCommentResult;
import com.repoguard.agent.mapper.GithubCommentPublicationBatchItemMapper;
import com.repoguard.agent.mapper.GithubCommentPublicationBatchMapper;
import com.repoguard.agent.mapper.GithubCommentPublicationMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class GithubCommentPublicationRecorderTest {

    private final GithubCommentPublicationMapper publicationMapper = Mockito.mock(GithubCommentPublicationMapper.class);
    private final GithubCommentPublicationBatchMapper batchMapper = Mockito.mock(GithubCommentPublicationBatchMapper.class);
    private final GithubCommentPublicationBatchItemMapper batchItemMapper = Mockito.mock(GithubCommentPublicationBatchItemMapper.class);
    private final GithubCommentPublicationRecorder recorder = new GithubCommentPublicationRecorder(
        publicationMapper,
        batchMapper,
        batchItemMapper
    );

    @Test
    void recordPublicationInsertsNewFindingPublication() {
        when(publicationMapper.selectOne(any())).thenReturn(null);

        GithubCommentPublication publication = recorder.recordPublication(521L, new GithubReviewCommentResult(
            1L,
            "README.md",
            8,
            "line",
            true,
            "published",
            "GitHub comment published",
            "https://github.com/comment/1",
            101L
        ));

        assertThat(publication.getTaskId()).isEqualTo(521L);
        assertThat(publication.getFindingId()).isEqualTo(1L);
        assertThat(publication.getTargetType()).isEqualTo("line");
        assertThat(publication.getSuccess()).isTrue();
        assertThat(publication.getPublishedAt()).isNotNull();
        verify(publicationMapper).insert(publication);
    }

    @Test
    void recordPublicationUpdatesExistingPublication() {
        GithubCommentPublication existing = new GithubCommentPublication();
        existing.setId(10L);
        existing.setTaskId(521L);
        existing.setFindingId(1L);
        existing.setCreatedAt(LocalDateTime.of(2026, 6, 18, 10, 0));
        when(publicationMapper.selectOne(any())).thenReturn(existing);

        GithubCommentPublication publication = recorder.recordPublication(521L, new GithubReviewCommentResult(
            1L,
            "README.md",
            8,
            "line",
            false,
            "failed",
            "403 Resource not accessible by integration",
            null,
            null
        ));

        assertThat(publication.getId()).isEqualTo(10L);
        assertThat(publication.getStatus()).isEqualTo("failed");
        assertThat(publication.getSuccess()).isFalse();
        assertThat(publication.getPublishedAt()).isNull();
        verify(publicationMapper).updateById(existing);
    }

    @Test
    void recordBatchPersistsBatchAndHistoryItems() {
        Mockito.doAnswer(invocation -> {
            GithubCommentPublicationBatch batch = invocation.getArgument(0);
            batch.setId(99L);
            return 1;
        }).when(batchMapper).insert(any(GithubCommentPublicationBatch.class));

        Long batchId = recorder.recordBatch(new GithubCommentPublishResponse(
            521L,
            2,
            1,
            1,
            0,
            1,
            List.of(
                item(1L, true, "published", "2026-06-18 11:00:00"),
                item(2L, true, "already_published", "2026-06-18 10:30:00")
            )
        ));

        assertThat(batchId).isEqualTo(99L);
        ArgumentCaptor<GithubCommentPublicationBatch> batchCaptor = ArgumentCaptor.forClass(GithubCommentPublicationBatch.class);
        verify(batchMapper).insert(batchCaptor.capture());
        assertThat(batchCaptor.getValue().getStatus()).isEqualTo("completed");
        assertThat(batchCaptor.getValue().getSucceededCount()).isEqualTo(1);
        assertThat(batchCaptor.getValue().getSkippedCount()).isEqualTo(1);

        ArgumentCaptor<GithubCommentPublicationBatchItem> itemCaptor = ArgumentCaptor.forClass(GithubCommentPublicationBatchItem.class);
        verify(batchItemMapper, Mockito.times(2)).insert(itemCaptor.capture());
        assertThat(itemCaptor.getAllValues()).allMatch(item -> item.getBatchId().equals(99L));
        assertThat(itemCaptor.getAllValues().getFirst().getPublishedAt()).isEqualTo(LocalDateTime.of(2026, 6, 18, 11, 0));
    }

    @Test
    void recordBatchMarksPartialFailureWhenSomePublishAttemptsFail() {
        Mockito.doAnswer(invocation -> {
            GithubCommentPublicationBatch batch = invocation.getArgument(0);
            batch.setId(100L);
            return 1;
        }).when(batchMapper).insert(any(GithubCommentPublicationBatch.class));

        recorder.recordBatch(new GithubCommentPublishResponse(
            521L,
            2,
            2,
            1,
            1,
            0,
            List.of(
                item(1L, true, "published", "2026-06-18 11:00:00"),
                item(2L, false, "failed", null)
            )
        ));

        ArgumentCaptor<GithubCommentPublicationBatch> batchCaptor = ArgumentCaptor.forClass(GithubCommentPublicationBatch.class);
        verify(batchMapper).insert(batchCaptor.capture());
        assertThat(batchCaptor.getValue().getStatus()).isEqualTo("partial_failed");
    }

    private GithubCommentPublishItem item(Long findingId, boolean success, String status, String publishedAt) {
        return new GithubCommentPublishItem(
            findingId,
            "README.md",
            8,
            "line",
            success,
            status,
            "message",
            null,
            null,
            null,
            success ? "https://github.com/comment/" + findingId : null,
            success ? findingId : null,
            publishedAt
        );
    }
}
