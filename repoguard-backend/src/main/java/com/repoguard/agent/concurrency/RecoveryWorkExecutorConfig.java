package com.repoguard.agent.concurrency;

import java.util.concurrent.ExecutorService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class RecoveryWorkExecutorConfig {

    static final String RECOVERY_WORK_EXECUTOR = "recoveryWorkExecutor";

    @Bean(name = RECOVERY_WORK_EXECUTOR, destroyMethod = "shutdown")
    ExecutorService recoveryWorkExecutor(BoundedExecutorFactory factory, AsyncExecutorProperties properties) {
        return factory.create(
            "recovery-work",
            properties.getRecoveryThreads(),
            properties.getRecoveryQueueCapacity()
        );
    }
}
