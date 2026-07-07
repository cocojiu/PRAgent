package com.repoguard.agent.messaging;

import com.repoguard.agent.config.RabbitReviewQueueProperties;
import com.repoguard.agent.entity.ReviewTask;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
class ReviewTaskPublishCompensationQuery {

    private final ReviewTaskPublishOutboxStore outboxStore;
    private final RabbitReviewQueueProperties properties;
    private final RabbitPublishCompensationPolicy compensationPolicy;

    ReviewTaskPublishCompensationQuery(
        ReviewTaskPublishOutboxStore outboxStore,
        RabbitReviewQueueProperties properties,
        RabbitPublishCompensationPolicy compensationPolicy
    ) {
        this.outboxStore = Objects.requireNonNull(outboxStore, "outboxStore");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.compensationPolicy = Objects.requireNonNull(compensationPolicy, "compensationPolicy");
    }

    List<ReviewTask> loadDueTasks(LocalDateTime now) {
        return outboxStore.loadDuePublishEvents(
            now,
            expiredBefore(now),
            maxAttempts(),
            batchSize()
        );
    }

    LocalDateTime expiredBefore(LocalDateTime now) {
        return compensationPolicy.expiredBefore(now, properties.getPublishCompensationLeaseMs());
    }

    LocalDateTime nextRetryAt(LocalDateTime now) {
        return compensationPolicy.nextRetryAt(now, properties);
    }

    int nextAttempt(Integer currentAttempts) {
        return compensationPolicy.nextAttempt(currentAttempts);
    }

    int maxAttempts() {
        return compensationPolicy.maxAttempts(properties);
    }

    int batchSize() {
        return compensationPolicy.batchSize(properties);
    }
}
