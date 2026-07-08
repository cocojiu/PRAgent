package com.repoguard.agent.service.impl;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class GithubCommentPublishExecutorConfig {

    static final String GITHUB_COMMENT_PUBLISH_EXECUTOR_SERVICE = "githubCommentPublishExecutorService";

    @Bean(name = GITHUB_COMMENT_PUBLISH_EXECUTOR_SERVICE, destroyMethod = "shutdown")
    ExecutorService githubCommentPublishExecutor() {
        return Executors.newFixedThreadPool(2, new GithubCommentPublishThreadFactory());
    }

    private static final class GithubCommentPublishThreadFactory implements ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "github-comment-publish-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
