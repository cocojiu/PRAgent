package com.repoguard.agent.notification;

import com.repoguard.agent.config.RabbitNotificationQueueProperties;
import com.repoguard.agent.entity.NotificationEvent;
import com.repoguard.agent.messaging.RabbitPublishCompensationPolicy;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
class NotificationPublishCompensationQuery {

    private final NotificationOutboxEventStore outboxEventStore;
    private final RabbitNotificationQueueProperties properties;
    private final RabbitPublishCompensationPolicy compensationPolicy;

    NotificationPublishCompensationQuery(
        NotificationOutboxEventStore outboxEventStore,
        RabbitNotificationQueueProperties properties,
        RabbitPublishCompensationPolicy compensationPolicy
    ) {
        this.outboxEventStore = Objects.requireNonNull(outboxEventStore, "outboxEventStore");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.compensationPolicy = Objects.requireNonNull(compensationPolicy, "compensationPolicy");
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
        return compensationPolicy.expiredBefore(now, properties.getPublishCompensationLeaseMs());
    }

    int maxAttempts() {
        return compensationPolicy.maxAttempts(properties);
    }

    int batchSize() {
        return compensationPolicy.batchSize(properties);
    }
}
