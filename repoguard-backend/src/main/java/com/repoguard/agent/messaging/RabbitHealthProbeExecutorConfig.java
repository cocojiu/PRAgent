package com.repoguard.agent.messaging;

import com.repoguard.agent.concurrency.AsyncExecutorProperties;
import com.repoguard.agent.concurrency.BoundedExecutorFactory;
import java.util.concurrent.ExecutorService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class RabbitHealthProbeExecutorConfig {

    static final String RABBIT_HEALTH_EXECUTOR = "rabbitHealthExecutor";

    @Bean(name = RABBIT_HEALTH_EXECUTOR, destroyMethod = "shutdown")
    ExecutorService rabbitHealthExecutor(BoundedExecutorFactory factory, AsyncExecutorProperties properties) {
        return factory.create(
            "rabbit-health",
            properties.getRabbitHealthThreads(),
            properties.getRabbitHealthQueueCapacity()
        );
    }
}
