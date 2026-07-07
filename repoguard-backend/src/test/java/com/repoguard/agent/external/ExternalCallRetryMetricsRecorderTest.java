package com.repoguard.agent.external;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.repoguard.agent.observability.ObservabilityThresholdMonitor;
import com.repoguard.agent.observability.RepoGuardMetrics;
import io.github.resilience4j.retry.event.RetryOnRetryEvent;
import org.junit.jupiter.api.Test;

class ExternalCallRetryMetricsRecorderTest {

    private final RepoGuardMetrics metrics = org.mockito.Mockito.mock(RepoGuardMetrics.class);
    private final ObservabilityThresholdMonitor thresholdMonitor = org.mockito.Mockito.mock(
        ObservabilityThresholdMonitor.class
    );
    private final ExternalCallRetryMetricsRecorder recorder = new ExternalCallRetryMetricsRecorder(
        metrics,
        thresholdMonitor
    );

    @Test
    void constructorRejectsMissingMetrics() {
        assertThatThrownBy(() -> new ExternalCallRetryMetricsRecorder(null, thresholdMonitor))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("metrics");
    }

    @Test
    void constructorRejectsMissingThresholdMonitor() {
        assertThatThrownBy(() -> new ExternalCallRetryMetricsRecorder(metrics, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("thresholdMonitor");
    }

    @Test
    void recordsClassifiedRetryAttempt() {
        RuntimeException cause = new RuntimeException("upstream failed");
        ExternalCallException classified = new ExternalCallException(
            "GitHub",
            "github_service_unavailable",
            true,
            502,
            "Bad gateway",
            cause
        );

        recorder.record(
            ignored -> classified,
            new RetryOnRetryEvent("repoguard-github", 2, cause, 0)
        );

        verify(metrics).externalCallRetried(classified, 2);
        verify(thresholdMonitor).externalCallRetry(classified, 2);
    }

    @Test
    void ignoresNullAndNonRuntimeRetryCauses() {
        recorder.record(ExternalCallErrorClassifier::github, null);
        recorder.record(
            ExternalCallErrorClassifier::github,
            new RetryOnRetryEvent("repoguard-github", 1, new Error("boom"), 0)
        );

        verify(metrics, never()).externalCallRetried(
            org.mockito.Mockito.any(),
            org.mockito.Mockito.anyInt()
        );
        verify(thresholdMonitor, never()).externalCallRetry(
            org.mockito.Mockito.any(),
            org.mockito.Mockito.anyInt()
        );
    }
}
