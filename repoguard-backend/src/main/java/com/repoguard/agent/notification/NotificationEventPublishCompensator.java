package com.repoguard.agent.notification;

import com.repoguard.agent.config.WorkerRuntimeEnabled;
import com.repoguard.agent.entity.NotificationEvent;
import com.repoguard.agent.messaging.RabbitPublishCompensationMetricsRecorder;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@WorkerRuntimeEnabled
public class NotificationEventPublishCompensator {

    private static final Logger LOGGER = LoggerFactory.getLogger(NotificationEventPublishCompensator.class);

    private final NotificationOutboxEventStore outboxEventStore;
    private final NotificationPublishCompensationQuery compensationQuery;
    private final NotificationEventPublishCoordinator publishCoordinator;
    private final RabbitPublishCompensationMetricsRecorder metricsRecorder;
    private final String instanceId;

    @Autowired
    public NotificationEventPublishCompensator(
        NotificationOutboxEventStore outboxEventStore,
        NotificationPublishCompensationQuery compensationQuery,
        NotificationEventPublishCoordinator publishCoordinator,
        RabbitPublishCompensationMetricsRecorder metricsRecorder
    ) {
        this(
            outboxEventStore,
            compensationQuery,
            publishCoordinator,
            metricsRecorder,
            "repoguard-notification-" + UUID.randomUUID()
        );
    }

    NotificationEventPublishCompensator(
        NotificationOutboxEventStore outboxEventStore,
        NotificationPublishCompensationQuery compensationQuery,
        NotificationEventPublishCoordinator publishCoordinator,
        RabbitPublishCompensationMetricsRecorder metricsRecorder,
        String instanceId
    ) {
        this.outboxEventStore = Objects.requireNonNull(outboxEventStore, "outboxEventStore");
        this.compensationQuery = Objects.requireNonNull(compensationQuery, "compensationQuery");
        this.publishCoordinator = Objects.requireNonNull(publishCoordinator, "publishCoordinator");
        this.metricsRecorder = Objects.requireNonNull(metricsRecorder, "metricsRecorder");
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
        if (!outboxEventStore.claimForPublish(event, compensationQuery.claim(claimedAt, instanceId))) {
            LOGGER.info(
                "Notification publish compensation skipped eventId={} eventKey={} operation=notification_publish_compensation result=claim_failed status={} retryCount={} maxAttempts={}",
                event.getId(),
                event.getEventKey(),
                event.getStatus(),
                event.getRetryCount(),
                compensationQuery.maxAttempts()
            );
            return;
        }
        NotificationPublishResult result = publishCoordinator.publish(event);
        if (result.success()) {
            metricsRecorder.recordSucceeded("notification");
            LOGGER.info(
                "Notification publish compensation completed eventId={} eventKey={} operation=notification_publish_compensation result=published retryCount={}",
                event.getId(),
                event.getEventKey(),
                event.getRetryCount()
            );
            return;
        }
        metricsRecorder.recordFailed("notification", result.failureReason());
        LOGGER.warn(
            "Notification publish compensation failed eventId={} eventKey={} operation=notification_publish_compensation result=publish_failed retryCount={} reason={}",
            event.getId(),
            event.getEventKey(),
            event.getRetryCount(),
            result.failureReason()
        );
    }
}
