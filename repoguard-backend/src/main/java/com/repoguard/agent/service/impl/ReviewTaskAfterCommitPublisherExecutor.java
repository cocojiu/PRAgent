package com.repoguard.agent.service.impl;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ReviewTaskAfterCommitPublisherExecutor implements Executor {

    private final ExecutorService delegate;

    @Autowired
    public ReviewTaskAfterCommitPublisherExecutor() {
        this(Executors.newFixedThreadPool(2, new ManualReviewPublishThreadFactory()));
    }

    ReviewTaskAfterCommitPublisherExecutor(ExecutorService delegate) {
        this.delegate = delegate;
    }

    @Override
    public void execute(Runnable command) {
        delegate.execute(command);
    }

    @PreDestroy
    public void shutdown() {
        delegate.shutdown();
    }

    private static class ManualReviewPublishThreadFactory implements ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "manual-review-publish-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
