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
    private final RabbitPublishCompensationSettings compensationSettings;

    ReviewTaskPublishCompensationQuery(
        ReviewTaskPublishOutboxStore outboxStore,
        RabbitReviewQueueProperties properties,
        RabbitPublishCompensationSettingsFactory settingsFactory
    ) {
        this.outboxStore = Objects.requireNonNull(outboxStore, "outboxStore");
        this.compensationSettings = Objects.requireNonNull(settingsFactory, "settingsFactory").create(properties);
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
        return compensationSettings.expiredBefore(now);
    }

    RabbitPublishClaim claim(LocalDateTime claimedAt, String instanceId) {
        return compensationSettings.claim(claimedAt, instanceId);
    }

    LocalDateTime nextRetryAt(LocalDateTime now) {
        return compensationSettings.nextRetryAt(now);
    }

    int nextAttempt(Integer currentAttempts) {
        return compensationSettings.nextAttempt(currentAttempts);
    }

    int maxAttempts() {
        return compensationSettings.maxAttempts();
    }

    int batchSize() {
        return compensationSettings.batchSize();
    }
}
