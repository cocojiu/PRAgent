package com.repoguard.agent.notification;

import com.repoguard.agent.config.WorkerRuntimeEnabled;
import com.repoguard.agent.entity.NotificationEvent;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@WorkerRuntimeEnabled
public class NotificationEventPublishCompensator {

    private final NotificationOutboxEventStore outboxEventStore;
    private final NotificationPublishCompensationQuery compensationQuery;
    private final NotificationEventPublishCoordinator publishCoordinator;
    private final String instanceId;

    @Autowired
    public NotificationEventPublishCompensator(
        NotificationOutboxEventStore outboxEventStore,
        NotificationPublishCompensationQuery compensationQuery,
        NotificationEventPublishCoordinator publishCoordinator
    ) {
        this(
            outboxEventStore,
            compensationQuery,
            publishCoordinator,
            "repoguard-notification-" + UUID.randomUUID()
        );
    }

    NotificationEventPublishCompensator(
        NotificationOutboxEventStore outboxEventStore,
        NotificationPublishCompensationQuery compensationQuery,
        NotificationEventPublishCoordinator publishCoordinator,
        String instanceId
    ) {
        this.outboxEventStore = Objects.requireNonNull(outboxEventStore, "outboxEventStore");
        this.compensationQuery = Objects.requireNonNull(compensationQuery, "compensationQuery");
        this.publishCoordinator = Objects.requireNonNull(publishCoordinator, "publishCoordinator");
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
    }

    @Scheduled(fixedDelayString = "${app.rabbit.notification.publish-compensation-interval-ms:60000}")
    public void compensate() {
        LocalDateTime now = LocalDateTime.now();
        List<NotificationEvent> events = compensationQuery.loadDueEvents(now);
        for (NotificationEvent event : events) {
            compensate(event);
        }
    }

    void compensate(NotificationEvent event) {
        LocalDateTime claimedAt = LocalDateTime.now();
        if (!outboxEventStore.claimForPublish(
            event,
            claimedAt,
            instanceId,
            compensationQuery.expiredBefore(claimedAt),
            compensationQuery.maxAttempts()
        )) {
            return;
        }
        publishCoordinator.publish(event);
    }
}
