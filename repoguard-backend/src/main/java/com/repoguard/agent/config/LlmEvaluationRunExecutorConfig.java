package com.repoguard.agent.config;

import com.repoguard.agent.concurrency.BoundedExecutorFactory;
import java.util.concurrent.ExecutorService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LlmEvaluationRunExecutorConfig {

    public static final String EVALUATION_RUN_EXECUTOR = "evaluationRunExecutor";

    @Bean(name = EVALUATION_RUN_EXECUTOR, destroyMethod = "shutdown")
    ExecutorService evaluationRunExecutor(
        BoundedExecutorFactory factory,
        LlmEvaluationRunProperties properties
    ) {
        return factory.create(
            "evaluation-run",
            Math.max(1, properties.getExecutorThreads()),
            Math.max(1, properties.getQueueCapacity())
        );
    }
}
