package com.repoguard.agent.notification.outbox;

import com.repoguard.agent.config.RabbitNotificationQueueProperties;
import com.repoguard.agent.entity.NotificationEvent;
import com.repoguard.agent.messaging.RabbitPublishClaim;
import com.repoguard.agent.messaging.RabbitPublishCompensationSettings;
import com.repoguard.agent.messaging.RabbitPublishCompensationSettingsFactory;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class NotificationPublishCompensationQuery {

    private final NotificationOutboxEventStore outboxEventStore;
    private final RabbitPublishCompensationSettings compensationSettings;

    public NotificationPublishCompensationQuery(
        NotificationOutboxEventStore outboxEventStore,
        RabbitNotificationQueueProperties properties,
        RabbitPublishCompensationSettingsFactory settingsFactory
    ) {
        this.outboxEventStore = Objects.requireNonNull(outboxEventStore, "outboxEventStore");
        this.compensationSettings = Objects.requireNonNull(settingsFactory, "settingsFactory").create(properties);
    }

    public List<NotificationEvent> loadDueEvents(LocalDateTime now) {
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

    public RabbitPublishClaim claim(LocalDateTime claimedAt, String instanceId) {
        return compensationSettings.claim(claimedAt, instanceId);
    }

    public int maxAttempts() {
        return compensationSettings.maxAttempts();
    }

    int batchSize() {
        return compensationSettings.batchSize();
    }
}
