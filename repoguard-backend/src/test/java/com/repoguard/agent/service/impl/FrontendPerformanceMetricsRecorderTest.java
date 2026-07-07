package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

import com.repoguard.agent.observability.RepoGuardMetrics;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class FrontendPerformanceMetricsRecorderTest {

    private final RepoGuardMetrics metrics = org.mockito.Mockito.mock(RepoGuardMetrics.class);
    private final FrontendPerformanceMetricsRecorder recorder = new FrontendPerformanceMetricsRecorder(metrics);

    @Test
    void constructorRejectsMissingMetrics() {
        assertThatThrownBy(() -> new FrontendPerformanceMetricsRecorder(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("metrics");
    }

    @Test
    void recordsApiWaterfallMetric() {
        Duration duration = Duration.ofMillis(48);

        recorder.recordApiWaterfallRequest(
            duration,
            "overview",
            "fetchDashboardSummary",
            "/api/v1/dashboard/summary",
            "GET",
            "200",
            "success"
        );

        verify(metrics).frontendApiWaterfallRequest(
            duration,
            "overview",
            "fetchDashboardSummary",
            "/api/v1/dashboard/summary",
            "GET",
            "200",
            "success"
        );
    }

    @Test
    void recordsLongTaskMetric() {
        Duration duration = Duration.ofMillis(83);

        recorder.recordLongTask(duration, "overview");

        verify(metrics).frontendLongTask(duration, "overview");
    }
}
