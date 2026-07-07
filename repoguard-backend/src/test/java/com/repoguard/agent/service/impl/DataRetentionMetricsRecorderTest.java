package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.repoguard.agent.dto.DataRetentionCleanupResponse;
import com.repoguard.agent.observability.RepoGuardMetrics;
import org.junit.jupiter.api.Test;

class DataRetentionMetricsRecorderTest {

    private final RepoGuardMetrics metrics = org.mockito.Mockito.mock(RepoGuardMetrics.class);
    private final DataRetentionMetricsRecorder recorder = new DataRetentionMetricsRecorder(metrics);

    @Test
    void constructorRejectsMissingMetrics() {
        assertThatThrownBy(() -> new DataRetentionMetricsRecorder(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("metrics");
    }

    @Test
    void recordsCleanupResponseWithTaskCounts() {
        recorder.record(new DataRetentionCleanupResponse(
            true,
            90,
            500,
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
}
