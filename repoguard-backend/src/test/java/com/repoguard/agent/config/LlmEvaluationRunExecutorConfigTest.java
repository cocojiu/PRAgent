package com.repoguard.agent.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.concurrency.AsyncExecutorProperties;
import com.repoguard.agent.concurrency.BoundedExecutorFactory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class LlmEvaluationRunExecutorConfigTest {

    @Test
    void registersBoundedEvaluationExecutor() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(
                LlmEvaluationRunExecutorConfig.class,
                LlmEvaluationRunProperties.class,
                AsyncExecutorProperties.class,
                BoundedExecutorFactory.class,
                SimpleMeterRegistry.class
            );
            context.refresh();

            ExecutorService executor = context.getBean(
                LlmEvaluationRunExecutorConfig.EVALUATION_RUN_EXECUTOR,
                ExecutorService.class
            );
            assertThat(executor).isNotNull();
            assertThat(context.getBean(LlmEvaluationRunProperties.class).getExecutorThreads()).isEqualTo(1);
        }
    }
}
