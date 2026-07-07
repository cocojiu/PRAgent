package com.repoguard.agent.messaging;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

import com.repoguard.agent.observability.RepoGuardMetrics;
import org.junit.jupiter.api.Test;

class RabbitPublishFailureMetricsRecorderTest {

    private final RepoGuardMetrics metrics = org.mockito.Mockito.mock(RepoGuardMetrics.class);
    private final RabbitPublishFailureMetricsRecorder recorder = new RabbitPublishFailureMetricsRecorder(metrics);

    @Test
    void constructorRejectsMissingMetrics() {
        assertThatThrownBy(() -> new RabbitPublishFailureMetricsRecorder(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("metrics");
    }

    @Test
    void recordsFailedPublishWithFailurePhaseAndReason() {
        recorder.recordFailed("notification", "confirm_timeout");

        verify(metrics).rabbitPublishFailed("notification", "confirm_timeout");
    }

    @Test
    void recordsFailedPublishWithTypedFailurePhase() {
        recorder.recordFailed(RabbitPublishFailurePhase.PUBLISH, "confirm_timeout");

        verify(metrics).rabbitPublishFailed("publish", "confirm_timeout");
    }

    @Test
    void rejectsMissingFailurePhase() {
        assertThatThrownBy(() -> recorder.recordFailed((RabbitPublishFailurePhase) null, "confirm_timeout"))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("failurePhase");
    }
}
