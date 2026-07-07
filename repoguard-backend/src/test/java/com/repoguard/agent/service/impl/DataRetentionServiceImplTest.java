package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.config.SystemSettings;
import com.repoguard.agent.config.SystemSettingsProvider;
import com.repoguard.agent.dto.DataRetentionCleanupAuditDto;
import com.repoguard.agent.dto.DataRetentionCleanupRequest;
import com.repoguard.agent.dto.PageResponse;
import com.repoguard.agent.entity.ChangedFile;
import com.repoguard.agent.entity.GithubCommentPublication;
import com.repoguard.agent.entity.GithubCommentPublicationBatch;
import com.repoguard.agent.entity.GithubCommentPublicationBatchItem;
import com.repoguard.agent.entity.ReviewFinding;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.entity.ReviewTimeline;
import com.repoguard.agent.mapper.ChangedFileMapper;
import com.repoguard.agent.mapper.GithubCommentPublicationBatchItemMapper;
import com.repoguard.agent.mapper.GithubCommentPublicationBatchMapper;
import com.repoguard.agent.mapper.GithubCommentPublicationMapper;
import com.repoguard.agent.mapper.ReviewFindingMapper;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.mapper.ReviewTimelineMapper;
import com.repoguard.agent.review.ReviewTaskStateMachine;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessResourceFailureException;

class DataRetentionServiceImplTest {

    private final ReviewTaskMapper reviewTaskMapper = org.mockito.Mockito.mock(ReviewTaskMapper.class);
    private final ChangedFileMapper changedFileMapper = org.mockito.Mockito.mock(ChangedFileMapper.class);
    private final ReviewFindingMapper reviewFindingMapper = org.mockito.Mockito.mock(ReviewFindingMapper.class);
    private final ReviewTimelineMapper reviewTimelineMapper = org.mockito.Mockito.mock(ReviewTimelineMapper.class);
    private final GithubCommentPublicationMapper githubCommentPublicationMapper = org.mockito.Mockito.mock(GithubCommentPublicationMapper.class);
    private final GithubCommentPublicationBatchMapper githubCommentPublicationBatchMapper = org.mockito.Mockito.mock(GithubCommentPublicationBatchMapper.class);
    private final GithubCommentPublicationBatchItemMapper githubCommentPublicationBatchItemMapper = org.mockito.Mockito.mock(GithubCommentPublicationBatchItemMapper.class);
    private final SystemSettingsProvider systemSettingsProvider = org.mockito.Mockito.mock(SystemSettingsProvider.class);
    private final DataRetentionMetricsRecorder metricsRecorder = org.mockito.Mockito.mock(DataRetentionMetricsRecorder.class);
    private final DataRetentionCleanupAuditRecorder auditRecorder = org.mockito.Mockito.mock(DataRetentionCleanupAuditRecorder.class);
    private final DataRetentionCleanupAuditQueryService auditQueryService = org.mockito.Mockito.mock(
        DataRetentionCleanupAuditQueryService.class
    );
    private final DataRetentionServiceImpl service = new DataRetentionServiceImpl(
        reviewTaskMapper,
        changedFileMapper,
        reviewFindingMapper,
        reviewTimelineMapper,
        githubCommentPublicationMapper,
        githubCommentPublicationBatchMapper,
        githubCommentPublicationBatchItemMapper,
        systemSettingsProvider,
        new ReviewTaskStateMachine(),
        metricsRecorder,
        auditRecorder,
        auditQueryService
    );

    @Test
    void cleanupDryRunUsesSavedRetentionDaysAndDoesNotDelete() {
        stubAuditStart(101L);
        when(systemSettingsProvider.getSettings()).thenReturn(systemSettings(30));
        when(reviewTaskMapper.selectCount(any())).thenReturn(2L);
        when(reviewTaskMapper.selectList(any())).thenReturn(List.of(task(1L), task(2L)));

        var response = service.cleanup(new DataRetentionCleanupRequest(null, 100, false, null, null));

        assertThat(response.executed()).isFalse();
        assertThat(response.cleanupBatchId()).isEqualTo(101L);
        assertThat(response.retentionDays()).isEqualTo(30);
        assertThat(response.backupReference()).isNull();
        assertThat(response.candidateTasks()).isEqualTo(2);
        assertThat(response.selectedTasks()).isEqualTo(2);
        verify(auditRecorder).complete(101L, response);
        verify(metricsRecorder).record(response);
        verify(changedFileMapper, never()).delete(any(Wrapper.class));
        verify(reviewTaskMapper, never()).delete(any(Wrapper.class));
    }

    @Test
    void cleanupExecuteDeletesChildrenBeforeTasks() {
        stubAuditStart(102L);
        when(reviewTaskMapper.selectCount(any())).thenReturn(1L);
        when(reviewTaskMapper.selectList(any())).thenReturn(List.of(task(9L)));
        when(githubCommentPublicationBatchItemMapper.delete(any())).thenReturn(3);
        when(githubCommentPublicationMapper.delete(any())).thenReturn(2);
        when(githubCommentPublicationBatchMapper.delete(any())).thenReturn(1);
        when(changedFileMapper.delete(any())).thenReturn(4);
        when(reviewTimelineMapper.delete(any())).thenReturn(5);
        when(reviewFindingMapper.delete(any())).thenReturn(6);
        when(reviewTaskMapper.delete(any())).thenReturn(1);

        var response = service.cleanup(new DataRetentionCleanupRequest(
            7,
            50,
            true,
            "backup://mysql/prod/2026-07-07T22:00:00",
            "CLEANUP"
        ));

        assertThat(response.executed()).isTrue();
        assertThat(response.cleanupBatchId()).isEqualTo(102L);
        assertThat(response.backupReference()).isEqualTo("backup://mysql/prod/2026-07-07T22:00:00");
        assertThat(response.deletedBatchItems()).isEqualTo(3);
        assertThat(response.deletedTasks()).isEqualTo(1);
        verify(auditRecorder).complete(102L, response);
        verify(metricsRecorder).record(response);
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
        assertThatThrownBy(() -> service.cleanup(new DataRetentionCleanupRequest(
            7,
            50,
            true,
            "backup://mysql/prod/2026-07-07T22:00:00",
            null
        )))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("CLEANUP");
        verify(metricsRecorder).recordFailure(
            org.mockito.Mockito.eq(true),
            org.mockito.Mockito.any(BusinessException.class)
        );
        verify(auditRecorder, never()).start(
            org.mockito.Mockito.anyBoolean(),
            org.mockito.Mockito.anyInt(),
            org.mockito.Mockito.anyInt(),
            org.mockito.Mockito.any(),
            org.mockito.Mockito.any()
        );
    }

    @Test
    void cleanupExecuteRequiresBackupReference() {
        assertThatThrownBy(() -> service.cleanup(new DataRetentionCleanupRequest(7, 50, true, "   ", "CLEANUP")))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("backupReference");
        verify(metricsRecorder).recordFailure(
            org.mockito.Mockito.eq(true),
            org.mockito.Mockito.any(BusinessException.class)
        );
        verify(auditRecorder, never()).start(
            org.mockito.Mockito.anyBoolean(),
            org.mockito.Mockito.anyInt(),
            org.mockito.Mockito.anyInt(),
            org.mockito.Mockito.any(),
            org.mockito.Mockito.any()
        );
    }

    @Test
    void cleanupFailureIsRecordedBeforeRethrow() {
        stubAuditStart(103L);
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("database unavailable");
        when(systemSettingsProvider.getSettings()).thenReturn(systemSettings(30));
        when(reviewTaskMapper.selectCount(any())).thenThrow(failure);

        assertThatThrownBy(() -> service.cleanup(new DataRetentionCleanupRequest(null, 100, false, null, null)))
            .isSameAs(failure);

        verify(auditRecorder).fail(103L, failure);
        verify(metricsRecorder).recordFailure(false, failure);
        verify(metricsRecorder, never()).record(any());
    }

    @Test
    void cleanupRejectsConcurrentRunBeforeStartingAnotherAudit() throws Exception {
        stubAuditStart(104L);
        CountDownLatch firstCleanupEnteredQuery = new CountDownLatch(1);
        CountDownLatch releaseFirstCleanup = new CountDownLatch(1);
        when(systemSettingsProvider.getSettings()).thenReturn(systemSettings(30));
        when(reviewTaskMapper.selectCount(any())).thenAnswer(invocation -> {
            firstCleanupEnteredQuery.countDown();
            assertThat(releaseFirstCleanup.await(5, TimeUnit.SECONDS)).isTrue();
            return 0L;
        });
        when(reviewTaskMapper.selectList(any())).thenReturn(List.of());

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> firstCleanup = executor.submit(() ->
                service.cleanup(new DataRetentionCleanupRequest(null, 100, false, null, null))
            );

            assertThat(firstCleanupEnteredQuery.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> service.cleanup(new DataRetentionCleanupRequest(
                7,
                50,
                true,
                "backup://mysql/prod/2026-07-07T22:00:00",
                "CLEANUP"
            )))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("正在执行");

            verify(metricsRecorder).recordFailure(
                org.mockito.Mockito.eq(true),
                org.mockito.Mockito.any(BusinessException.class)
            );
            verify(auditRecorder, times(1)).start(
                org.mockito.Mockito.anyBoolean(),
                org.mockito.Mockito.anyInt(),
                org.mockito.Mockito.anyInt(),
                org.mockito.Mockito.any(),
                org.mockito.Mockito.any()
            );

            releaseFirstCleanup.countDown();
            assertThat(firstCleanup.get(5, TimeUnit.SECONDS)).isNotNull();
        } finally {
            releaseFirstCleanup.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void listCleanupAuditsDelegatesToQueryService() {
        PageResponse<DataRetentionCleanupAuditDto> expected = new PageResponse<>(List.of(), 0);
        when(auditQueryService.listAudits(2, 50, "execute", "completed", "backup://mysql/prod/2026-07-07T22:00:00"))
            .thenReturn(expected);

        var response = service.listCleanupAudits(
            2,
            50,
            "execute",
            "completed",
            "backup://mysql/prod/2026-07-07T22:00:00"
        );

        assertThat(response).isSameAs(expected);
        verify(auditQueryService).listAudits(
            2,
            50,
            "execute",
            "completed",
            "backup://mysql/prod/2026-07-07T22:00:00"
        );
    }

    @Test
    void constructorRejectsMissingStateMachine() {
        assertThatThrownBy(() -> new DataRetentionServiceImpl(
            reviewTaskMapper,
            changedFileMapper,
            reviewFindingMapper,
            reviewTimelineMapper,
            githubCommentPublicationMapper,
            githubCommentPublicationBatchMapper,
            githubCommentPublicationBatchItemMapper,
            systemSettingsProvider,
            null,
            metricsRecorder,
            auditRecorder,
            auditQueryService
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("reviewTaskStateMachine");
    }

    @Test
    void constructorRejectsMissingMetricsRecorder() {
        assertThatThrownBy(() -> new DataRetentionServiceImpl(
            reviewTaskMapper,
            changedFileMapper,
            reviewFindingMapper,
            reviewTimelineMapper,
            githubCommentPublicationMapper,
            githubCommentPublicationBatchMapper,
            githubCommentPublicationBatchItemMapper,
            systemSettingsProvider,
            new ReviewTaskStateMachine(),
            null,
            auditRecorder,
            auditQueryService
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("metricsRecorder");
    }

    @Test
    void constructorRejectsMissingAuditRecorder() {
        assertThatThrownBy(() -> new DataRetentionServiceImpl(
            reviewTaskMapper,
            changedFileMapper,
            reviewFindingMapper,
            reviewTimelineMapper,
            githubCommentPublicationMapper,
            githubCommentPublicationBatchMapper,
            githubCommentPublicationBatchItemMapper,
            systemSettingsProvider,
            new ReviewTaskStateMachine(),
            metricsRecorder,
            null,
            auditQueryService
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("auditRecorder");
    }

    @Test
    void constructorRejectsMissingAuditQueryService() {
        assertThatThrownBy(() -> new DataRetentionServiceImpl(
            reviewTaskMapper,
            changedFileMapper,
            reviewFindingMapper,
            reviewTimelineMapper,
            githubCommentPublicationMapper,
            githubCommentPublicationBatchMapper,
            githubCommentPublicationBatchItemMapper,
            systemSettingsProvider,
            new ReviewTaskStateMachine(),
            metricsRecorder,
            auditRecorder,
            null
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("auditQueryService");
    }

    private void stubAuditStart(Long cleanupBatchId) {
        when(auditRecorder.start(
            org.mockito.Mockito.anyBoolean(),
            org.mockito.Mockito.anyInt(),
            org.mockito.Mockito.anyInt(),
            org.mockito.Mockito.any(),
            org.mockito.Mockito.any()
        )).thenReturn(cleanupBatchId);
    }

    private ReviewTask task(Long id) {
        ReviewTask task = new ReviewTask();
        task.setId(id);
        return task;
    }

    private SystemSettings systemSettings(Integer retentionDays) {
        return new SystemSettings(
            true,
            "RepoGuard",
            "zh-CN",
            "Asia/Shanghai",
            retentionDays,
            2000,
            true,
            true,
            true,
            true,
            true,
            null,
            true,
            true,
            false,
            7
        );
    }
}
