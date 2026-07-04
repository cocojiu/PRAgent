package com.repoguard.agent.worker;

import com.repoguard.agent.config.RabbitReviewQueueProperties;
import java.time.LocalDateTime;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
class ReviewTaskRecoveryPolicy {

    private static final long MIN_EXECUTION_TIMEOUT_MS = 60000;
    private static final int MIN_BATCH_SIZE = 1;

    private final RabbitReviewQueueProperties properties;

    ReviewTaskRecoveryPolicy(RabbitReviewQueueProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    LocalDateTime expiredBefore(LocalDateTime now) {
        return now.minusNanos(executionTimeoutMs() * 1_000_000);
    }

    long executionTimeoutMs() {
        return Math.max(MIN_EXECUTION_TIMEOUT_MS, properties.getReviewExecutionTimeoutMs());
    }

    int batchSize() {
        return Math.max(MIN_BATCH_SIZE, properties.getReviewRecoveryBatchSize());
    }
}
