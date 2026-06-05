package com.repoguard.agent.worker;

import static org.mockito.Mockito.verify;

import com.repoguard.agent.messaging.ReviewTaskMessage;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class ReviewTaskWorkerTest {

    @Test
    void handleDelegatesMessageToExecutor() {
        ReviewTaskExecutor executor = org.mockito.Mockito.mock(ReviewTaskExecutor.class);
        ReviewTaskMessage message = new ReviewTaskMessage(
            42L,
            "repo-guard-demo",
            "spring-boot-demo",
            512,
            "a1b2c3d",
            LocalDateTime.parse("2026-06-05T18:00:00")
        );

        new ReviewTaskWorker(executor).handle(message);

        verify(executor).execute(message);
    }
}
