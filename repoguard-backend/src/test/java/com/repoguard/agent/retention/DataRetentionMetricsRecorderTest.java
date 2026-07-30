package com.repoguard.agent.retention;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.DataRetentionCleanupResponse;
import com.repoguard.agent.observability.ObservabilityThresholdMonitor;
import com.repoguard.agent.observability.RepoGuardMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class DataRetentionMetricsRecorderTest {

    private final RepoGuardMetrics metrics = org.mockito.Mockito.mock(RepoGuardMetrics.class);
    private final ObservabilityThresholdMonitor thresholdMonitor = org.mockito.Mockito.mock(
        ObservabilityThresholdMonitor.class
    );
    private final DataRetentionMetricsRecorder recorder = new DataRetentionMetricsRecorder(
        metrics,
        thresholdMonitor
    );

    @Test
    void constructorRejectsMissingMetrics() {
        assertThatThrownBy(() -> new DataRetentionMetricsRecorder(null, thresholdMonitor))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("metrics");
    }

    @Test
    void constructorRejectsMissingThresholdMonitor() {
        assertThatThrownBy(() -> new DataRetentionMetricsRecorder(metrics, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("thresholdMonitor");
    }

    @Test
    void recordsCleanupResponseWithTaskCounts() {
        recorder.record(new DataRetentionCleanupResponse(
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
        ));

        verify(metrics).dataRetentionCleanup(true, 12, 5, 5);
    }

    @Test
    void ignoresNullResponse() {
        recorder.record(null);

        verify(metrics, never()).dataRetentionCleanup(
            org.mockito.Mockito.anyBoolean(),
            org.mockito.Mockito.anyLong(),
            org.mockito.Mockito.anyInt(),
            org.mockito.Mockito.anyInt()
        );
    }

    @Test
    void recordsStableFailureReasons() {
        recorder.recordFailure(true, new BusinessException(ErrorCode.BAD_REQUEST, "bad confirm"));
        recorder.recordFailure(false, new DataAccessResourceFailureException("database unavailable"));
        recorder.recordFailure(true, new IllegalStateException("boom"));

        verify(metrics).dataRetentionCleanupFailed(true, "bad_request");
        verify(metrics).dataRetentionCleanupFailed(false, "database_error");
        verify(metrics).dataRetentionCleanupFailed(true, "cleanup_failed");
        verify(thresholdMonitor).dataRetentionCleanupFailure(true, "bad_request");
        verify(thresholdMonitor).dataRetentionCleanupFailure(false, "database_error");
        verify(thresholdMonitor).dataRetentionCleanupFailure(true, "cleanup_failed");
    }
}
