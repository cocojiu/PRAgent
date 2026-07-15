package com.repoguard.agent.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.repoguard.agent.mapper.ChangedFileMapper;
import com.repoguard.agent.mapper.GithubCommentPublicationBatchItemMapper;
import com.repoguard.agent.mapper.GithubCommentPublicationBatchMapper;
import com.repoguard.agent.mapper.GithubCommentPublicationMapper;
import com.repoguard.agent.mapper.ReviewFindingMapper;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.mapper.ReviewTimelineMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class DataRetentionDeleteExecutorTest {

    private final ChangedFileMapper changedFileMapper = org.mockito.Mockito.mock(ChangedFileMapper.class);
    private final ReviewFindingMapper reviewFindingMapper = org.mockito.Mockito.mock(ReviewFindingMapper.class);
    private final ReviewTimelineMapper reviewTimelineMapper = org.mockito.Mockito.mock(ReviewTimelineMapper.class);
    private final GithubCommentPublicationMapper publicationMapper =
        org.mockito.Mockito.mock(GithubCommentPublicationMapper.class);
    private final GithubCommentPublicationBatchMapper batchMapper =
        org.mockito.Mockito.mock(GithubCommentPublicationBatchMapper.class);
    private final GithubCommentPublicationBatchItemMapper batchItemMapper =
        org.mockito.Mockito.mock(GithubCommentPublicationBatchItemMapper.class);
    private final ReviewTaskMapper reviewTaskMapper = org.mockito.Mockito.mock(ReviewTaskMapper.class);
    private final DataRetentionDeleteExecutor deleteExecutor = new DataRetentionDeleteExecutor(
        changedFileMapper,
        reviewFindingMapper,
        reviewTimelineMapper,
        publicationMapper,
        batchMapper,
        batchItemMapper,
        reviewTaskMapper
    );

    @Test
    void deletesDependentRowsBeforeReviewTasksAndReturnsAllCounts() {
        when(batchItemMapper.delete(any())).thenReturn(3);
        when(publicationMapper.delete(any())).thenReturn(2);
        when(batchMapper.delete(any())).thenReturn(1);
        when(changedFileMapper.delete(any())).thenReturn(4);
        when(reviewTimelineMapper.delete(any())).thenReturn(5);
        when(reviewFindingMapper.delete(any())).thenReturn(6);
        when(reviewTaskMapper.delete(any())).thenReturn(1);

        DataRetentionDeleteExecutor.DeletionResult result = deleteExecutor.delete(List.of(7L, 9L));

        assertThat(result).isEqualTo(new DataRetentionDeleteExecutor.DeletionResult(3, 2, 1, 4, 5, 6, 1));
        InOrder order = inOrder(
            batchItemMapper,
            publicationMapper,
            batchMapper,
            changedFileMapper,
            reviewTimelineMapper,
            reviewFindingMapper,
            reviewTaskMapper
        );
        order.verify(batchItemMapper).delete(any());
        order.verify(publicationMapper).delete(any());
        order.verify(batchMapper).delete(any());
        order.verify(changedFileMapper).delete(any());
        order.verify(reviewTimelineMapper).delete(any());
        order.verify(reviewFindingMapper).delete(any());
        order.verify(reviewTaskMapper).delete(any());
    }

    @Test
    void rejectsEmptySelectionWithoutDeletingAnything() {
        assertThatThrownBy(() -> deleteExecutor.delete(List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("taskIds");

        verifyNoInteractions(
            batchItemMapper,
            publicationMapper,
            batchMapper,
            changedFileMapper,
            reviewTimelineMapper,
            reviewFindingMapper,
            reviewTaskMapper
        );
    }
}
