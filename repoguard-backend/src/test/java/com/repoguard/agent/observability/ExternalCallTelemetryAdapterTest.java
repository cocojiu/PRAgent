package com.repoguard.agent.observability;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

import com.repoguard.agent.external.ExternalCallException;
import org.junit.jupiter.api.Test;

class ExternalCallTelemetryAdapterTest {

    private final RepoGuardMetrics metrics = org.mockito.Mockito.mock(RepoGuardMetrics.class);
    private final ObservabilityThresholdMonitor thresholdMonitor = org.mockito.Mockito.mock(
        ObservabilityThresholdMonitor.class
    );

    @Test
    void recordsMetricAndThresholdSignalThroughExternalPort() {
        ExternalCallException failure = new ExternalCallException(
            "GitHub",
            "github_service_unavailable",
            true,
            502,
            "Bad gateway",
            new RuntimeException("upstream failed")
        );
        ExternalCallTelemetryAdapter telemetry = new ExternalCallTelemetryAdapter(metrics, thresholdMonitor);

        telemetry.recordRetry(failure, 2);

        verify(metrics).externalCallRetried(failure, 2);
        verify(thresholdMonitor).externalCallRetry(failure, 2);
    }

    @Test
    void constructorRejectsMissingCollaborators() {
        assertThatThrownBy(() -> new ExternalCallTelemetryAdapter(null, thresholdMonitor))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("metrics");
        assertThatThrownBy(() -> new ExternalCallTelemetryAdapter(metrics, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("thresholdMonitor");
    }
}
