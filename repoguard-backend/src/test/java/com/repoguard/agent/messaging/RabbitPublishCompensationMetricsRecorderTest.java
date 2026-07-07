package com.repoguard.agent.messaging;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

import com.repoguard.agent.observability.RepoGuardMetrics;
import org.junit.jupiter.api.Test;

class RabbitPublishCompensationMetricsRecorderTest {

    private final RepoGuardMetrics metrics = org.mockito.Mockito.mock(RepoGuardMetrics.class);
    private final RabbitPublishCompensationMetricsRecorder recorder =
        new RabbitPublishCompensationMetricsRecorder(metrics);

    @Test
    void constructorRejectsMissingMetrics() {
        assertThatThrownBy(() -> new RabbitPublishCompensationMetricsRecorder(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("metrics");
    }

    @Test
    void recordsSucceededCompensationWithFailurePhase() {
        recorder.recordSucceeded("notification");

        verify(metrics).rabbitPublishCompensationSucceeded("notification");
    }

    @Test
    void recordsFailedCompensationWithFailurePhaseAndReason() {
        recorder.recordFailed("publish", "confirm_timeout");

        verify(metrics).rabbitPublishCompensationFailed("publish", "confirm_timeout");
    }
}
