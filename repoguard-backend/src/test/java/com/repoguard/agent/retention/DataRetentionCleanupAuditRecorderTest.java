package com.repoguard.agent.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.DataRetentionCleanupResponse;
import com.repoguard.agent.entity.DataRetentionCleanupAudit;
import com.repoguard.agent.mapper.DataRetentionCleanupAuditMapper;
import com.repoguard.agent.retention.DataRetentionDeleteExecutor;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DataRetentionCleanupAuditRecorderTest {

    private final DataRetentionCleanupAuditMapper auditMapper = org.mockito.Mockito.mock(
        DataRetentionCleanupAuditMapper.class
    );
    private final DataRetentionCleanupAuditRecorder recorder = new DataRetentionCleanupAuditRecorder(auditMapper);

    @Test
    void constructorRejectsMissingMapper() {
        assertThatThrownBy(() -> new DataRetentionCleanupAuditRecorder(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("auditMapper");
    }

    @Test
    void startCreatesStartedAuditBatchAndReturnsGeneratedId() {
        LocalDateTime cutoff = LocalDateTime.of(2026, 7, 7, 22, 0);
        when(auditMapper.insert(any(DataRetentionCleanupAudit.class))).thenAnswer(invocation -> {
            DataRetentionCleanupAudit audit = invocation.getArgument(0);
            audit.setId(77L);
            return 1;
        });

        Long result = recorder.start(true, 90, 500, "backup://mysql/prod/2026-07-07T22:00:00", cutoff);

        ArgumentCaptor<DataRetentionCleanupAudit> auditCaptor = ArgumentCaptor.forClass(DataRetentionCleanupAudit.class);
        verify(auditMapper).insert(auditCaptor.capture());
        DataRetentionCleanupAudit audit = auditCaptor.getValue();
        assertThat(result).isEqualTo(77L);
        assertThat(audit.getMode()).isEqualTo("execute");
        assertThat(audit.getStatus()).isEqualTo("STARTED");
        assertThat(audit.getRetentionDays()).isEqualTo(90);
        assertThat(audit.getMaxTasks()).isEqualTo(500);
        assertThat(audit.getBackupReference()).isEqualTo("backup://mysql/prod/2026-07-07T22:00:00");
        assertThat(audit.getCutoffTime()).isEqualTo(cutoff);
        assertThat(audit.getCandidateTasks()).isZero();
        assertThat(audit.getDeletedTasks()).isZero();
        assertThat(audit.getCreatedAt()).isNotNull();
        assertThat(audit.getUpdatedAt()).isNotNull();
    }

    @Test
    void completeUpdatesCountsAndCompletionTime() {
        DataRetentionCleanupResponse response = new DataRetentionCleanupResponse(
            true,
            77L,
            90,
            500,
            "backup://mysql/prod/2026-07-07T22:00:00",
            "2026-07-07 22:00:00",
            12,
            5,
            2,
            1,
            1,
            3,
            4,
            5,
            5
        );

        recorder.complete(77L, response);

        ArgumentCaptor<DataRetentionCleanupAudit> auditCaptor = ArgumentCaptor.forClass(DataRetentionCleanupAudit.class);
        verify(auditMapper).updateById(auditCaptor.capture());
        DataRetentionCleanupAudit audit = auditCaptor.getValue();
        assertThat(audit.getId()).isEqualTo(77L);
        assertThat(audit.getStatus()).isEqualTo("COMPLETED");
        assertThat(audit.getCandidateTasks()).isEqualTo(12);
        assertThat(audit.getSelectedTasks()).isEqualTo(5);
        assertThat(audit.getDeletedBatchItems()).isEqualTo(2);
        assertThat(audit.getDeletedTasks()).isEqualTo(5);
        assertThat(audit.getCompletedAt()).isNotNull();
        assertThat(audit.getUpdatedAt()).isEqualTo(audit.getCompletedAt());
    }

    @Test
    void completeIgnoresMissingAuditContext() {
        recorder.complete(null, new DataRetentionCleanupResponse(
            false,
            0L,
            90,
            500,
            null,
            "2026-07-07 22:00:00",
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0
        ));

        verify(auditMapper, never()).updateById(any(DataRetentionCleanupAudit.class));
    }

    @Test
    void failUpdatesStableReasonAndTruncatedFailureMessage() {
        String longMessage = "x".repeat(600);

        recorder.fail(77L, new BusinessException(ErrorCode.BAD_REQUEST, longMessage));

        ArgumentCaptor<DataRetentionCleanupAudit> auditCaptor = ArgumentCaptor.forClass(DataRetentionCleanupAudit.class);
        verify(auditMapper).updateById(auditCaptor.capture());
        DataRetentionCleanupAudit audit = auditCaptor.getValue();
        assertThat(audit.getId()).isEqualTo(77L);
        assertThat(audit.getStatus()).isEqualTo("FAILED");
        assertThat(audit.getFailureReason()).isEqualTo("bad_request");
        assertThat(audit.getFailureMessage()).hasSize(512);
        assertThat(audit.getCompletedAt()).isNotNull();
        assertThat(audit.getUpdatedAt()).isEqualTo(audit.getCompletedAt());
    }

    @Test
    void failIgnoresMissingAuditContext() {
        recorder.fail(null, new IllegalStateException("boom"));

        verify(auditMapper, never()).updateById(any(DataRetentionCleanupAudit.class));
    }

    @Test
    void failWithSliceProgressRecordsPartialCountsAndCompletedSlices() {
        recorder.fail(
            77L,
            new org.springframework.dao.DataAccessResourceFailureException("database unavailable"),
            12L,
            120,
            2,
            3,
            new DataRetentionDeleteExecutor.DeletionResult(3, 2, 1, 4, 5, 6, 100)
        );

        ArgumentCaptor<DataRetentionCleanupAudit> auditCaptor = ArgumentCaptor.forClass(DataRetentionCleanupAudit.class);
        verify(auditMapper).updateById(auditCaptor.capture());
        DataRetentionCleanupAudit audit = auditCaptor.getValue();
        assertThat(audit.getId()).isEqualTo(77L);
        assertThat(audit.getStatus()).isEqualTo("FAILED");
        assertThat(audit.getFailureReason()).isEqualTo("database_error");
        assertThat(audit.getFailureMessage()).isEqualTo("已提交 2/3 个分片: database unavailable");
        assertThat(audit.getCandidateTasks()).isEqualTo(12L);
        assertThat(audit.getSelectedTasks()).isEqualTo(120);
        assertThat(audit.getDeletedBatchItems()).isEqualTo(3);
        assertThat(audit.getDeletedPublications()).isEqualTo(2);
        assertThat(audit.getDeletedBatches()).isEqualTo(1);
        assertThat(audit.getDeletedChangedFiles()).isEqualTo(4);
        assertThat(audit.getDeletedTimelines()).isEqualTo(5);
        assertThat(audit.getDeletedFindings()).isEqualTo(6);
        assertThat(audit.getDeletedTasks()).isEqualTo(100);
        assertThat(audit.getCompletedAt()).isNotNull();
        assertThat(audit.getUpdatedAt()).isEqualTo(audit.getCompletedAt());
    }

    @Test
    void failWithSliceProgressIgnoresMissingAuditContext() {
        recorder.fail(
            null,
            new IllegalStateException("boom"),
            1L,
            1,
            0,
            1,
            new DataRetentionDeleteExecutor.DeletionResult(0, 0, 0, 0, 0, 0, 0)
        );

        verify(auditMapper, never()).updateById(any(DataRetentionCleanupAudit.class));
    }
}
