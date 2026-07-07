package com.repoguard.agent.notification;

import com.repoguard.agent.config.RabbitNotificationQueueProperties;
import com.repoguard.agent.entity.NotificationEvent;
import com.repoguard.agent.messaging.RabbitPublishCompensationPolicy;
import com.repoguard.agent.messaging.RabbitPublishCompensationSettings;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
class NotificationPublishCompensationQuery {

    private final NotificationOutboxEventStore outboxEventStore;
    private final RabbitPublishCompensationSettings compensationSettings;

    NotificationPublishCompensationQuery(
        NotificationOutboxEventStore outboxEventStore,
        RabbitNotificationQueueProperties properties,
        RabbitPublishCompensationPolicy compensationPolicy
    ) {
        this.outboxEventStore = Objects.requireNonNull(outboxEventStore, "outboxEventStore");
        this.compensationSettings = new RabbitPublishCompensationSettings(properties, compensationPolicy);
    }

    List<NotificationEvent> loadDueEvents(LocalDateTime now) {
        return outboxEventStore.loadDuePublishEvents(
            now,
            expiredBefore(now),
            maxAttempts(),
            batchSize()
        );
    }

    LocalDateTime expiredBefore(LocalDateTime now) {
        return compensationSettings.expiredBefore(now);
    }

    int maxAttempts() {
        return compensationSettings.maxAttempts();
    }

    int batchSize() {
        return compensationSettings.batchSize();
    }
}
