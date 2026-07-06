package com.repoguard.agent.service.impl;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class ManualReviewCleanupExecutorConfig {

    static final String MANUAL_REVIEW_CLEANUP_EXECUTOR = "manualReviewCleanupExecutor";

    @Bean(name = MANUAL_REVIEW_CLEANUP_EXECUTOR, destroyMethod = "shutdown")
    ScheduledExecutorService manualReviewCleanupExecutor() {
        return newManualReviewCleanupExecutor();
    }

    static ScheduledExecutorService newManualReviewCleanupExecutor() {
        return Executors.newSingleThreadScheduledExecutor(new ManualReviewCleanupThreadFactory());
    }

    private static final class ManualReviewCleanupThreadFactory implements ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "manual-review-cleanup-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
