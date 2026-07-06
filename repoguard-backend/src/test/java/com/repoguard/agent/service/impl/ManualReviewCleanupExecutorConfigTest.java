package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class ManualReviewCleanupExecutorConfigTest {

    @Test
    void registersNamedManualReviewCleanupExecutor() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(ManualReviewCleanupExecutorConfig.class, ManualReviewIdempotencyCoordinator.class);
            context.refresh();

            ScheduledExecutorService executor = context.getBean(
                ManualReviewCleanupExecutorConfig.MANUAL_REVIEW_CLEANUP_EXECUTOR,
                ScheduledExecutorService.class
            );
            ManualReviewIdempotencyCoordinator coordinator =
                context.getBean(ManualReviewIdempotencyCoordinator.class);

            assertThat(executor).isNotNull();
            assertThat(coordinator).isNotNull();
        }
    }
}
