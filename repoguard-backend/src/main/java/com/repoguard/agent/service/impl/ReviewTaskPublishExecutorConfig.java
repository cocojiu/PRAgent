package com.repoguard.agent.service.impl;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class ReviewTaskPublishExecutorConfig {

    static final String REVIEW_TASK_AFTER_COMMIT_EXECUTOR = "reviewTaskAfterCommitExecutor";

    @Bean(name = REVIEW_TASK_AFTER_COMMIT_EXECUTOR, destroyMethod = "shutdown")
    ExecutorService reviewTaskAfterCommitExecutor() {
        return Executors.newFixedThreadPool(2, new ManualReviewPublishThreadFactory());
    }

    private static final class ManualReviewPublishThreadFactory implements ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "manual-review-publish-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
