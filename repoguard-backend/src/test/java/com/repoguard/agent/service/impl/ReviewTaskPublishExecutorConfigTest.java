package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class ReviewTaskPublishExecutorConfigTest {

    @Test
    void registersNamedAfterCommitPublishExecutor() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(ReviewTaskPublishExecutorConfig.class, ReviewTaskAfterCommitPublisherExecutor.class);
            context.refresh();

            ExecutorService executor = context.getBean(
                ReviewTaskPublishExecutorConfig.REVIEW_TASK_AFTER_COMMIT_EXECUTOR,
                ExecutorService.class
            );
            ReviewTaskAfterCommitPublisherExecutor publisherExecutor =
                context.getBean(ReviewTaskAfterCommitPublisherExecutor.class);

            assertThat(executor).isNotNull();
            assertThat(publisherExecutor).isNotNull();
        }
    }
}
