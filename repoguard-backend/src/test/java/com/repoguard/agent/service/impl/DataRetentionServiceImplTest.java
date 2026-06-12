package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.dto.DataRetentionCleanupRequest;
import com.repoguard.agent.entity.ChangedFile;
import com.repoguard.agent.entity.GithubCommentPublication;
import com.repoguard.agent.entity.GithubCommentPublicationBatch;
import com.repoguard.agent.entity.GithubCommentPublicationBatchItem;
import com.repoguard.agent.entity.ReviewFinding;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.entity.ReviewTimeline;
import com.repoguard.agent.entity.SystemSettingsConfig;
import com.repoguard.agent.mapper.ChangedFileMapper;
import com.repoguard.agent.mapper.GithubCommentPublicationBatchItemMapper;
import com.repoguard.agent.mapper.GithubCommentPublicationBatchMapper;
import com.repoguard.agent.mapper.GithubCommentPublicationMapper;
import com.repoguard.agent.mapper.ReviewFindingMapper;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.mapper.ReviewTimelineMapper;
import com.repoguard.agent.mapper.SystemSettingsConfigMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class DataRetentionServiceImplTest {

    private final ReviewTaskMapper reviewTaskMapper = org.mockito.Mockito.mock(ReviewTaskMapper.class);
    private final ChangedFileMapper changedFileMapper = org.mockito.Mockito.mock(ChangedFileMapper.class);
    private final ReviewFindingMapper reviewFindingMapper = org.mockito.Mockito.mock(ReviewFindingMapper.class);
    private final ReviewTimelineMapper reviewTimelineMapper = org.mockito.Mockito.mock(ReviewTimelineMapper.class);
    private final GithubCommentPublicationMapper githubCommentPublicationMapper = org.mockito.Mockito.mock(GithubCommentPublicationMapper.class);
    private final GithubCommentPublicationBatchMapper githubCommentPublicationBatchMapper = org.mockito.Mockito.mock(GithubCommentPublicationBatchMapper.class);
    private final GithubCommentPublicationBatchItemMapper githubCommentPublicationBatchItemMapper = org.mockito.Mockito.mock(GithubCommentPublicationBatchItemMapper.class);
    private final SystemSettingsConfigMapper systemSettingsConfigMapper = org.mockito.Mockito.mock(SystemSettingsConfigMapper.class);
    private final DataRetentionServiceImpl service = new DataRetentionServiceImpl(
        reviewTaskMapper,
        changedFileMapper,
        reviewFindingMapper,
        reviewTimelineMapper,
        githubCommentPublicationMapper,
        githubCommentPublicationBatchMapper,
        githubCommentPublicationBatchItemMapper,
        systemSettingsConfigMapper
    );

    @Test
    void cleanupDryRunUsesSavedRetentionDaysAndDoesNotDelete() {
        SystemSettingsConfig settings = new SystemSettingsConfig();
        settings.setRetentionDays(30);
        when(systemSettingsConfigMapper.selectById(1L)).thenReturn(settings);
        when(reviewTaskMapper.selectCount(any())).thenReturn(2L);
        when(reviewTaskMapper.selectList(any())).thenReturn(List.of(task(1L), task(2L)));

        var response = service.cleanup(new DataRetentionCleanupRequest(null, 100, false, null));

        assertThat(response.executed()).isFalse();
        assertThat(response.retentionDays()).isEqualTo(30);
        assertThat(response.candidateTasks()).isEqualTo(2);
        assertThat(response.selectedTasks()).isEqualTo(2);
        verify(changedFileMapper, never()).delete(any(Wrapper.class));
        verify(reviewTaskMapper, never()).delete(any(Wrapper.class));
    }

    @Test
    void cleanupExecuteDeletesChildrenBeforeTasks() {
        when(reviewTaskMapper.selectCount(any())).thenReturn(1L);
        when(reviewTaskMapper.selectList(any())).thenReturn(List.of(task(9L)));
        when(githubCommentPublicationBatchItemMapper.delete(any())).thenReturn(3);
        when(githubCommentPublicationMapper.delete(any())).thenReturn(2);
        when(githubCommentPublicationBatchMapper.delete(any())).thenReturn(1);
        when(changedFileMapper.delete(any())).thenReturn(4);
        when(reviewTimelineMapper.delete(any())).thenReturn(5);
        when(reviewFindingMapper.delete(any())).thenReturn(6);
        when(reviewTaskMapper.delete(any())).thenReturn(1);

        var response = service.cleanup(new DataRetentionCleanupRequest(7, 50, true, "CLEANUP"));

        assertThat(response.executed()).isTrue();
        assertThat(response.deletedBatchItems()).isEqualTo(3);
        assertThat(response.deletedTasks()).isEqualTo(1);
        InOrder order = inOrder(
            githubCommentPublicationBatchItemMapper,
            githubCommentPublicationMapper,
            githubCommentPublicationBatchMapper,
            changedFileMapper,
            reviewTimelineMapper,
            reviewFindingMapper,
            reviewTaskMapper
        );
        order.verify(githubCommentPublicationBatchItemMapper).delete(any());
        order.verify(githubCommentPublicationMapper).delete(any());
        order.verify(githubCommentPublicationBatchMapper).delete(any());
        order.verify(changedFileMapper).delete(any());
        order.verify(reviewTimelineMapper).delete(any());
        order.verify(reviewFindingMapper).delete(any());
        order.verify(reviewTaskMapper).delete(any());
    }

    @Test
    void cleanupExecuteRequiresConfirmationText() {
        assertThatThrownBy(() -> service.cleanup(new DataRetentionCleanupRequest(7, 50, true, null)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("CLEANUP");
    }

    private ReviewTask task(Long id) {
        ReviewTask task = new ReviewTask();
        task.setId(id);
        return task;
    }
}
