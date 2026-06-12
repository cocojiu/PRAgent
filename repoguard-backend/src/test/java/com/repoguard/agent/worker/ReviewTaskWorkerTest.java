package com.repoguard.agent.worker;

import static org.mockito.Mockito.verify;

import com.repoguard.agent.messaging.ReviewTaskMessage;
import com.repoguard.agent.observability.RepoGuardMetrics;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class ReviewTaskWorkerTest {

    @Test
    void handleDelegatesMessageToExecutor() {
        ReviewTaskExecutor executor = org.mockito.Mockito.mock(ReviewTaskExecutor.class);
        RepoGuardMetrics metrics = org.mockito.Mockito.mock(RepoGuardMetrics.class);
        ReviewTaskMessage message = new ReviewTaskMessage(
            42L,
            "repo-guard-demo",
            "spring-boot-demo",
            512,
            "a1b2c3d",
            LocalDateTime.parse("2026-06-05T18:00:00")
        );

        new ReviewTaskWorker(executor, metrics).handle(message);

        verify(executor).execute(message);
        verify(metrics).rabbitMessageConsumed(org.mockito.ArgumentMatchers.any(), org.mockito.Mockito.eq("success"));
    }
}
