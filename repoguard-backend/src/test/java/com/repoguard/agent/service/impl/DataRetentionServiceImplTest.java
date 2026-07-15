package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.config.DataRetentionProperties;
import com.repoguard.agent.config.SystemSettings;
import com.repoguard.agent.config.SystemSettingsProvider;
import com.repoguard.agent.dto.DataRetentionCleanupAuditDto;
import com.repoguard.agent.dto.DataRetentionCleanupRequest;
import com.repoguard.agent.dto.PageResponse;
import com.repoguard.agent.retention.DataRetentionArchiveWriter;
import com.repoguard.agent.retention.DataRetentionCandidateQuery;
import com.repoguard.agent.retention.DataRetentionDeleteExecutor;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class DataRetentionServiceImplTest {

    private final DataRetentionCandidateQuery candidateQuery = org.mockito.Mockito.mock(DataRetentionCandidateQuery.class);
    private final DataRetentionArchiveWriter archiveWriter = org.mockito.Mockito.mock(DataRetentionArchiveWriter.class);
    private final DataRetentionDeleteExecutor deleteExecutor = org.mockito.Mockito.mock(DataRetentionDeleteExecutor.class);
    private final SystemSettingsProvider systemSettingsProvider = org.mockito.Mockito.mock(SystemSettingsProvider.class);
    private final DataRetentionMetricsRecorder metricsRecorder = org.mockito.Mockito.mock(DataRetentionMetricsRecorder.class);
    private final DataRetentionCleanupAuditRecorder auditRecorder = org.mockito.Mockito.mock(DataRetentionCleanupAuditRecorder.class);
    private final DataRetentionCleanupAuditQueryService auditQueryService = org.mockito.Mockito.mock(
        DataRetentionCleanupAuditQueryService.class
    );
    private final DataRetentionCleanupLeaseStore leaseStore = org.mockito.Mockito.mock(DataRetentionCleanupLeaseStore.class);
    private final DataRetentionProperties dataRetentionProperties = new DataRetentionProperties();
    private final DataRetentionServiceImpl service = new DataRetentionServiceImpl(
        candidateQuery,
        archiveWriter,
        deleteExecutor,
        systemSettingsProvider,
        metricsRecorder,
        auditRecorder,
        auditQueryService,
        leaseStore,
        dataRetentionProperties
    );

    @Test
    void cleanupDryRunUsesSavedRetentionDaysAndDoesNotDelete() {
        stubLeaseAcquired();
        stubAuditStart(101L);
        when(systemSettingsProvider.getSettings()).thenReturn(systemSettings(30));
        when(candidateQuery.select(any(), org.mockito.Mockito.eq(100)))
            .thenReturn(new DataRetentionCandidateQuery.CandidateSelection(2L, List.of(1L, 2L)));

        var response = service.cleanup(new DataRetentionCleanupRequest(null, 100, false, null, null));

        assertThat(response.executed()).isFalse();
        assertThat(response.cleanupBatchId()).isEqualTo(101L);
        assertThat(response.retentionDays()).isEqualTo(30);
        assertThat(response.backupReference()).isNull();
        assertThat(response.candidateTasks()).isEqualTo(2);
        assertThat(response.selectedTasks()).isEqualTo(2);
        verify(auditRecorder).complete(101L, response);
        verify(metricsRecorder).record(response);
        verify(leaseStore).release(org.mockito.Mockito.any(DataRetentionCleanupLeaseStore.Lease.class));
        verify(archiveWriter, never()).write(
            org.mockito.Mockito.anyLong(),
            org.mockito.Mockito.any(),
            org.mockito.Mockito.anyList()
        );
        verify(deleteExecutor, never()).delete(org.mockito.Mockito.anyList());
    }

    @Test
    void cleanupExecuteArchivesBeforeDeletingChildrenAndTasks() {
        stubLeaseAcquired();
        stubAuditStart(102L);
        when(candidateQuery.select(any(), org.mockito.Mockito.eq(50)))
            .thenReturn(new DataRetentionCandidateQuery.CandidateSelection(1L, List.of(9L)));
        when(deleteExecutor.delete(List.of(9L))).thenReturn(new DataRetentionDeleteExecutor.DeletionResult(
            3,
            2,
            1,
            4,
            5,
            6,
            1
        ));

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
        verify(leaseStore).release(org.mockito.Mockito.any(DataRetentionCleanupLeaseStore.Lease.class));
        InOrder order = inOrder(archiveWriter, deleteExecutor);
        order.verify(archiveWriter).write(
            102L,
            "backup://mysql/prod/2026-07-07T22:00:00",
            List.of(9L)
        );
        order.verify(deleteExecutor).delete(List.of(9L));
    }

    @Test
    void cleanupExecuteDoesNotDeleteWhenArchiveSummaryFails() {
        stubLeaseAcquired();
        stubAuditStart(105L);
        when(candidateQuery.select(any(), org.mockito.Mockito.eq(50)))
            .thenReturn(new DataRetentionCandidateQuery.CandidateSelection(1L, List.of(11L)));
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("archive unavailable");
        org.mockito.Mockito.doThrow(failure).when(archiveWriter).write(
            105L,
            "backup://mysql/prod/2026-07-07T22:00:00",
            List.of(11L)
        );

        assertThatThrownBy(() -> service.cleanup(new DataRetentionCleanupRequest(
            7,
            50,
            true,
            "backup://mysql/prod/2026-07-07T22:00:00",
            "CLEANUP"
        )))
            .isSameAs(failure);

        verify(auditRecorder).fail(105L, failure);
        verify(metricsRecorder).recordFailure(true, failure);
        verify(deleteExecutor, never()).delete(org.mockito.Mockito.anyList());
        verify(leaseStore).release(org.mockito.Mockito.any(DataRetentionCleanupLeaseStore.Lease.class));
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
        verify(leaseStore, never()).acquire();
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
        verify(leaseStore, never()).acquire();
    }

    @Test
    void cleanupExecuteRejectsMalformedBackupReferenceBeforeAcquiringLease() {
        assertThatThrownBy(() -> service.cleanup(new DataRetentionCleanupRequest(
            7,
            50,
            true,
            "backup://mysql/../prod/2026-07-07T22:00:00",
            "CLEANUP"
        )))
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
        verify(leaseStore, never()).acquire();
    }

    @Test
    void cleanupExecuteRejectsMaxTasksAboveConfiguredLimitBeforeAcquiringLease() {
        dataRetentionProperties.setCleanupMaxTasksPerRun(25);

        assertThatThrownBy(() -> service.cleanup(new DataRetentionCleanupRequest(
            7,
            50,
            true,
            "backup://mysql/prod/2026-07-07T22:00:00",
            "CLEANUP"
        )))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("maxTasks")
            .hasMessageContaining("25");
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
        verify(leaseStore, never()).acquire();
    }

    @Test
    void cleanupFailureIsRecordedBeforeRethrow() {
        stubLeaseAcquired();
        stubAuditStart(103L);
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("database unavailable");
        when(systemSettingsProvider.getSettings()).thenReturn(systemSettings(30));
        when(candidateQuery.select(any(), org.mockito.Mockito.eq(100))).thenThrow(failure);

        assertThatThrownBy(() -> service.cleanup(new DataRetentionCleanupRequest(null, 100, false, null, null)))
            .isSameAs(failure);

        verify(auditRecorder).fail(103L, failure);
        verify(metricsRecorder).recordFailure(false, failure);
        verify(metricsRecorder, never()).record(any());
        verify(leaseStore).release(org.mockito.Mockito.any(DataRetentionCleanupLeaseStore.Lease.class));
    }

    @Test
    void cleanupCompletesAuditAndReleasesLeaseOnlyAfterTransactionCommit() {
        stubLeaseAcquired();
        stubAuditStart(106L);
        when(systemSettingsProvider.getSettings()).thenReturn(systemSettings(30));
        when(candidateQuery.select(any(), org.mockito.Mockito.eq(100)))
            .thenReturn(new DataRetentionCandidateQuery.CandidateSelection(0L, List.of()));
        List<TransactionSynchronization> synchronizations = List.of();
        boolean transactionCompleted = false;
        beginTransactionSynchronization();
        try {
            var response = service.cleanup(new DataRetentionCleanupRequest(null, 100, false, null, null));
            synchronizations = TransactionSynchronizationManager.getSynchronizations();

            verify(auditRecorder, never()).complete(106L, response);
            verify(metricsRecorder, never()).record(response);
            verify(leaseStore, never()).release(any());

            synchronizations.forEach(TransactionSynchronization::afterCommit);
            synchronizations.forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
            transactionCompleted = true;

            verify(auditRecorder).complete(106L, response);
            verify(metricsRecorder).record(response);
            verify(leaseStore).release(org.mockito.Mockito.any(DataRetentionCleanupLeaseStore.Lease.class));
            verify(auditRecorder, never()).fail(org.mockito.Mockito.eq(106L), any());
        } finally {
            finishTransactionSynchronization(synchronizations, transactionCompleted);
        }
    }

    @Test
    void cleanupMarksAuditFailedAndReleasesLeaseWhenCommitRollsBack() {
        stubLeaseAcquired();
        stubAuditStart(107L);
        when(systemSettingsProvider.getSettings()).thenReturn(systemSettings(30));
        when(candidateQuery.select(any(), org.mockito.Mockito.eq(100)))
            .thenReturn(new DataRetentionCandidateQuery.CandidateSelection(0L, List.of()));
        List<TransactionSynchronization> synchronizations = List.of();
        boolean transactionCompleted = false;
        beginTransactionSynchronization();
        try {
            var response = service.cleanup(new DataRetentionCleanupRequest(null, 100, false, null, null));
            synchronizations = TransactionSynchronizationManager.getSynchronizations();

            synchronizations.forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
            transactionCompleted = true;

            verify(auditRecorder, never()).complete(107L, response);
            verify(auditRecorder).fail(
                org.mockito.Mockito.eq(107L),
                org.mockito.ArgumentMatchers.isA(IllegalStateException.class)
            );
            verify(metricsRecorder).recordFailure(
                org.mockito.Mockito.eq(false),
                org.mockito.ArgumentMatchers.isA(IllegalStateException.class)
            );
            verify(leaseStore).release(org.mockito.Mockito.any(DataRetentionCleanupLeaseStore.Lease.class));
        } finally {
            finishTransactionSynchronization(synchronizations, transactionCompleted);
        }
    }

    @Test
    void cleanupRejectsWhenDatabaseLeaseIsOwnedByAnotherInstance() {
        when(leaseStore.acquire()).thenReturn(null);

        assertThatThrownBy(() -> service.cleanup(new DataRetentionCleanupRequest(null, 100, false, null, null)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("其它实例");

        verify(metricsRecorder).recordFailure(
            org.mockito.Mockito.eq(false),
            org.mockito.Mockito.any(BusinessException.class)
        );
        verify(auditRecorder, never()).start(
            org.mockito.Mockito.anyBoolean(),
            org.mockito.Mockito.anyInt(),
            org.mockito.Mockito.anyInt(),
            org.mockito.Mockito.any(),
            org.mockito.Mockito.any()
        );
        verify(leaseStore).release(null);
    }

    @Test
    void cleanupRejectsConcurrentRunBeforeStartingAnotherAudit() throws Exception {
        stubLeaseAcquired();
        stubAuditStart(104L);
        CountDownLatch firstCleanupEnteredQuery = new CountDownLatch(1);
        CountDownLatch releaseFirstCleanup = new CountDownLatch(1);
        when(systemSettingsProvider.getSettings()).thenReturn(systemSettings(30));
        when(candidateQuery.select(any(), org.mockito.Mockito.eq(100))).thenAnswer(invocation -> {
            firstCleanupEnteredQuery.countDown();
            assertThat(releaseFirstCleanup.await(5, TimeUnit.SECONDS)).isTrue();
            return new DataRetentionCandidateQuery.CandidateSelection(0L, List.of());
        });

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
    void constructorRejectsMissingCandidateQuery() {
        assertThatThrownBy(() -> new DataRetentionServiceImpl(
            null,
            archiveWriter,
            deleteExecutor,
            systemSettingsProvider,
            metricsRecorder,
            auditRecorder,
            auditQueryService,
            leaseStore,
            dataRetentionProperties
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("candidateQuery");
    }

    @Test
    void constructorRejectsMissingArchiveWriter() {
        assertThatThrownBy(() -> new DataRetentionServiceImpl(
            candidateQuery,
            null,
            deleteExecutor,
            systemSettingsProvider,
            metricsRecorder,
            auditRecorder,
            auditQueryService,
            leaseStore,
            dataRetentionProperties
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("archiveWriter");
    }

    @Test
    void constructorRejectsMissingDeleteExecutor() {
        assertThatThrownBy(() -> new DataRetentionServiceImpl(
            candidateQuery,
            archiveWriter,
            null,
            systemSettingsProvider,
            metricsRecorder,
            auditRecorder,
            auditQueryService,
            leaseStore,
            dataRetentionProperties
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("deleteExecutor");
    }

    @Test
    void constructorRejectsMissingMetricsRecorder() {
        assertThatThrownBy(() -> new DataRetentionServiceImpl(
            candidateQuery,
            archiveWriter,
            deleteExecutor,
            systemSettingsProvider,
            null,
            auditRecorder,
            auditQueryService,
            leaseStore,
            dataRetentionProperties
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("metricsRecorder");
    }

    @Test
    void constructorRejectsMissingAuditRecorder() {
        assertThatThrownBy(() -> new DataRetentionServiceImpl(
            candidateQuery,
            archiveWriter,
            deleteExecutor,
            systemSettingsProvider,
            metricsRecorder,
            null,
            auditQueryService,
            leaseStore,
            dataRetentionProperties
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("auditRecorder");
    }

    @Test
    void constructorRejectsMissingAuditQueryService() {
        assertThatThrownBy(() -> new DataRetentionServiceImpl(
            candidateQuery,
            archiveWriter,
            deleteExecutor,
            systemSettingsProvider,
            metricsRecorder,
            auditRecorder,
            null,
            leaseStore,
            dataRetentionProperties
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("auditQueryService");
    }

    @Test
    void constructorRejectsMissingLeaseStore() {
        assertThatThrownBy(() -> new DataRetentionServiceImpl(
            candidateQuery,
            archiveWriter,
            deleteExecutor,
            systemSettingsProvider,
            metricsRecorder,
            auditRecorder,
            auditQueryService,
            null,
            dataRetentionProperties
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("leaseStore");
    }

    @Test
    void constructorRejectsMissingDataRetentionProperties() {
        assertThatThrownBy(() -> new DataRetentionServiceImpl(
            candidateQuery,
            archiveWriter,
            deleteExecutor,
            systemSettingsProvider,
            metricsRecorder,
            auditRecorder,
            auditQueryService,
            leaseStore,
            null
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("dataRetentionProperties");
    }

    private void stubLeaseAcquired() {
        when(leaseStore.acquire()).thenReturn(new DataRetentionCleanupLeaseStore.Lease("owner-1"));
    }

    private void beginTransactionSynchronization() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }

    private void finishTransactionSynchronization(
        List<TransactionSynchronization> synchronizations,
        boolean transactionCompleted
    ) {
        if (!transactionCompleted) {
            synchronizations.forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
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
