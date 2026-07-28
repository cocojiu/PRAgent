package com.repoguard.agent.worker;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rabbitmq.client.Channel;
import com.repoguard.agent.messaging.ReviewTaskMessage;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class ReviewTaskWorkerTest {

    @Test
    void handleAcknowledgesMessageAfterSuccessfulExecution() throws Exception {
        ReviewTaskExecutor executor = org.mockito.Mockito.mock(ReviewTaskExecutor.class);
        ReviewTaskWorkerMetricsRecorder metricsRecorder = org.mockito.Mockito.mock(ReviewTaskWorkerMetricsRecorder.class);
        Channel channel = org.mockito.Mockito.mock(Channel.class);
        ReviewTaskMessage message = message();
        when(metricsRecorder.startedAt()).thenReturn(100L);
        when(metricsRecorder.elapsedMillis(100L)).thenReturn(35L);

        new ReviewTaskWorker(
            executor,
            metricsRecorder,
            new ReviewLogContextFormatter(),
            new ReviewExecutionFailureClassifier()
        ).handle(message, channel, 99L);

        verify(executor).execute(message);
        verify(channel).basicAck(99L, false);
        verify(metricsRecorder).recordConsumed(100L, "success");
    }

    @Test
    void handleRejectsMessageWithoutRequeueWhenExecutionFails() throws Exception {
        ReviewTaskExecutor executor = org.mockito.Mockito.mock(ReviewTaskExecutor.class);
        ReviewTaskWorkerMetricsRecorder metricsRecorder = org.mockito.Mockito.mock(ReviewTaskWorkerMetricsRecorder.class);
        Channel channel = org.mockito.Mockito.mock(Channel.class);
        ReviewTaskMessage message = message();
        when(metricsRecorder.startedAt()).thenReturn(200L);
        when(metricsRecorder.elapsedMillis(200L)).thenReturn(21L);
        doThrow(new IllegalStateException("boom")).when(executor).execute(message);

        new ReviewTaskWorker(
            executor,
            metricsRecorder,
            new ReviewLogContextFormatter(),
            new ReviewExecutionFailureClassifier()
        ).handle(message, channel, 100L);

        verify(channel).basicReject(100L, false);
        verify(metricsRecorder).recordConsumed(200L, "rejected", "review_execution_failed");
    }

    @Test
    void handleRejectsFatalErrorExactlyOnceAndRethrowsIt() throws Exception {
        ReviewTaskExecutor executor = org.mockito.Mockito.mock(ReviewTaskExecutor.class);
        ReviewTaskWorkerMetricsRecorder metricsRecorder = org.mockito.Mockito.mock(ReviewTaskWorkerMetricsRecorder.class);
        Channel channel = org.mockito.Mockito.mock(Channel.class);
        ReviewTaskMessage message = message();
        AssertionError failure = new AssertionError("fatal");
        when(metricsRecorder.startedAt()).thenReturn(300L);
        when(metricsRecorder.elapsedMillis(300L)).thenReturn(8L);
        doThrow(failure).when(executor).execute(message);

        ReviewTaskWorker worker = new ReviewTaskWorker(
            executor,
            metricsRecorder,
            new ReviewLogContextFormatter(),
            new ReviewExecutionFailureClassifier()
        );

        assertThatThrownBy(() -> worker.handle(message, channel, 101L)).isSameAs(failure);
        verify(channel).basicReject(101L, false);
        verify(channel, never()).basicAck(101L, false);
        verify(metricsRecorder).recordConsumed(300L, "rejected", "review_execution_error");
    }

    @Test
    void telemetryFailureAfterAckDoesNotRejectAcknowledgedMessage() throws Exception {
        ReviewTaskExecutor executor = org.mockito.Mockito.mock(ReviewTaskExecutor.class);
        ReviewTaskWorkerMetricsRecorder metricsRecorder = org.mockito.Mockito.mock(ReviewTaskWorkerMetricsRecorder.class);
        Channel channel = org.mockito.Mockito.mock(Channel.class);
        ReviewTaskMessage message = message();
        when(metricsRecorder.startedAt()).thenReturn(400L);
        doThrow(new IllegalStateException("metrics unavailable"))
            .when(metricsRecorder)
            .recordConsumed(400L, "success");

        ReviewTaskWorker worker = new ReviewTaskWorker(
            executor,
            metricsRecorder,
            new ReviewLogContextFormatter(),
            new ReviewExecutionFailureClassifier()
        );

        assertThatThrownBy(() -> worker.handle(message, channel, 102L))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("metrics unavailable");
        verify(channel).basicAck(102L, false);
        verify(channel, never()).basicReject(102L, false);
    }

    private ReviewTaskMessage message() {
        return new ReviewTaskMessage(
            42L,
            "repo-guard-demo",
            "spring-boot-demo",
            512,
            "a1b2c3d",
            LocalDateTime.parse("2026-06-05T18:00:00")
        );
    }
}
