package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.concurrency.AsyncExecutorProperties;
import com.repoguard.agent.concurrency.BoundedExecutorFactory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.ThreadPoolExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class LlmChunkReviewExecutorConfigTest {

    @Test
    void registersNamedLlmChunkReviewExecutorWithBulkheadAlignedDefaultThreads() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(
                LlmChunkReviewExecutorConfig.class,
                AsyncExecutorProperties.class,
                BoundedExecutorFactory.class,
                SimpleMeterRegistry.class
            );
            context.refresh();

            ThreadPoolExecutor executor = context.getBean(
                LlmChunkReviewExecutorConfig.LLM_CHUNK_REVIEW_EXECUTOR,
                ThreadPoolExecutor.class
            );

            assertThat(executor).isNotNull();
            assertThat(executor.getMaximumPoolSize()).isEqualTo(4);
        }
    }
}
