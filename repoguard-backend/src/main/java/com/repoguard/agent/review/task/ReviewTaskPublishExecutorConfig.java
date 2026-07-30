package com.repoguard.agent.review.task;

import java.util.concurrent.ExecutorService;
import com.repoguard.agent.concurrency.AsyncExecutorProperties;
import com.repoguard.agent.concurrency.BoundedExecutorFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class ReviewTaskPublishExecutorConfig {

    static final String REVIEW_TASK_AFTER_COMMIT_EXECUTOR = "reviewTaskAfterCommitExecutor";

    @Bean(name = REVIEW_TASK_AFTER_COMMIT_EXECUTOR, destroyMethod = "shutdown")
    ExecutorService reviewTaskAfterCommitExecutor(BoundedExecutorFactory factory, AsyncExecutorProperties properties) {
        return factory.create(
            "review-after-commit",
            properties.getReviewPublishThreads(),
            properties.getReviewPublishQueueCapacity()
        );
    }
}
