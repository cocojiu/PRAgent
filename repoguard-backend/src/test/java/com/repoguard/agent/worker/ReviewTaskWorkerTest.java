package com.repoguard.agent.worker;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.rabbitmq.client.Channel;
import com.repoguard.agent.messaging.ReviewTaskMessage;
import com.repoguard.agent.observability.RepoGuardMetrics;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class ReviewTaskWorkerTest {

    @Test
    void handleAcknowledgesMessageAfterSuccessfulExecution() throws Exception {
        ReviewTaskExecutor executor = org.mockito.Mockito.mock(ReviewTaskExecutor.class);
        RepoGuardMetrics metrics = org.mockito.Mockito.mock(RepoGuardMetrics.class);
        Channel channel = org.mockito.Mockito.mock(Channel.class);
        ReviewTaskMessage message = message();

        new ReviewTaskWorker(executor, metrics).handle(message, channel, 99L);

        verify(executor).execute(message);
        verify(channel).basicAck(99L, false);
        verify(metrics).rabbitMessageConsumed(org.mockito.ArgumentMatchers.any(), org.mockito.Mockito.eq("success"));
    }

    @Test
    void handleRejectsMessageWithoutRequeueWhenExecutionFails() throws Exception {
        ReviewTaskExecutor executor = org.mockito.Mockito.mock(ReviewTaskExecutor.class);
        RepoGuardMetrics metrics = org.mockito.Mockito.mock(RepoGuardMetrics.class);
        Channel channel = org.mockito.Mockito.mock(Channel.class);
        ReviewTaskMessage message = message();
        doThrow(new IllegalStateException("boom")).when(executor).execute(message);

        new ReviewTaskWorker(executor, metrics).handle(message, channel, 100L);

        verify(channel).basicReject(100L, false);
        verify(metrics).rabbitMessageConsumed(org.mockito.ArgumentMatchers.any(), org.mockito.Mockito.eq("rejected"));
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
