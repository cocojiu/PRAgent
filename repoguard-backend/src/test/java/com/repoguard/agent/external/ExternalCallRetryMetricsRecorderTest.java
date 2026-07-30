package com.repoguard.agent.external;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.github.resilience4j.retry.event.RetryOnRetryEvent;
import org.junit.jupiter.api.Test;

class ExternalCallRetryMetricsRecorderTest {

    private final ExternalCallTelemetry telemetry = org.mockito.Mockito.mock(ExternalCallTelemetry.class);
    private final ExternalCallRetryMetricsRecorder recorder = new ExternalCallRetryMetricsRecorder(telemetry);

    @Test
    void constructorRejectsMissingTelemetry() {
        assertThatThrownBy(() -> new ExternalCallRetryMetricsRecorder(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("telemetry");
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

        verify(telemetry).recordRetry(classified, 2);
    }

    @Test
    void ignoresNullAndNonRuntimeRetryCauses() {
        recorder.record(ExternalCallErrorClassifier::github, null);
        recorder.record(
            ExternalCallErrorClassifier::github,
            new RetryOnRetryEvent("repoguard-github", 1, new Error("boom"), 0)
        );

        verify(telemetry, never()).recordRetry(
            org.mockito.Mockito.any(),
            org.mockito.Mockito.anyInt()
        );
    }
}
