package com.repoguard.agent.review;

import java.util.concurrent.ExecutorService;
import com.repoguard.agent.concurrency.AsyncExecutorProperties;
import com.repoguard.agent.concurrency.BoundedExecutorFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class LlmChunkReviewExecutorConfig {

    static final String LLM_CHUNK_REVIEW_EXECUTOR = "llmChunkReviewExecutor";

    @Bean(name = LLM_CHUNK_REVIEW_EXECUTOR, destroyMethod = "shutdown")
    ExecutorService llmChunkReviewExecutor(BoundedExecutorFactory factory, AsyncExecutorProperties properties) {
        return factory.create(
            "llm-chunk",
            properties.getLlmChunkThreads(),
            properties.getLlmChunkQueueCapacity()
        );
    }
}
