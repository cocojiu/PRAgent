package com.repoguard.agent.notification;

import com.repoguard.agent.config.SchedulerRuntimeEnabled;
import com.repoguard.agent.concurrency.RecoveryWorkDispatcher;
import com.repoguard.agent.entity.NotificationEvent;
import com.repoguard.agent.messaging.RabbitPublishCompensationMetricsRecorder;
import com.repoguard.agent.messaging.RabbitPublishCompensationOutcome;
import com.repoguard.agent.messaging.RabbitPublishFailurePhase;
import com.repoguard.agent.notification.outbox.NotificationPublishCompensationQuery;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@SchedulerRuntimeEnabled
public class NotificationEventPublishCompensator {

    private static final Logger LOGGER = LoggerFactory.getLogger(NotificationEventPublishCompensator.class);

    private final NotificationPublishCompensationQuery compensationQuery;
    private final NotificationEventPublishCoordinator publishCoordinator;
    private final RabbitPublishCompensationMetricsRecorder metricsRecorder;
    private final RecoveryWorkDispatcher recoveryWorkDispatcher;

    @Autowired
    public NotificationEventPublishCompensator(
        NotificationPublishCompensationQuery compensationQuery,
        NotificationEventPublishCoordinator publishCoordinator,
        RabbitPublishCompensationMetricsRecorder metricsRecorder,
        RecoveryWorkDispatcher recoveryWorkDispatcher
    ) {
        this.compensationQuery = Objects.requireNonNull(compensationQuery, "compensationQuery");
        this.publishCoordinator = Objects.requireNonNull(publishCoordinator, "publishCoordinator");
        this.metricsRecorder = Objects.requireNonNull(metricsRecorder, "metricsRecorder");
        this.recoveryWorkDispatcher = Objects.requireNonNull(
            recoveryWorkDispatcher,
            "recoveryWorkDispatcher"
        );
    }

    NotificationEventPublishCompensator(
        NotificationPublishCompensationQuery compensationQuery,
        NotificationEventPublishCoordinator publishCoordinator,
        RabbitPublishCompensationMetricsRecorder metricsRecorder
    ) {
        this(
            compensationQuery,
            publishCoordinator,
            metricsRecorder,
            new RecoveryWorkDispatcher(Runnable::run)
        );
    }

    @Scheduled(fixedDelayString = "${app.rabbit.notification.publish-compensation-interval-ms:60000}")
    public void compensate() {
        LocalDateTime now = LocalDateTime.now();
        List<NotificationEvent> events = compensationQuery.loadDueEvents(now);
        for (NotificationEvent event : events) {
            if (!recoveryWorkDispatcher.submit(
                "notification_publish_compensation",
                () -> compensate(event)
            )) {
                LOGGER.warn(
                    "Notification publish compensation deferred eventId={} operation=notification_publish_compensation result=executor_rejected",
                    event.getId()
                );
            }
        }
    }

    void compensate(NotificationEvent event) {
        NotificationPublishResult result = publishCoordinator.publish(event);
        if (!result.attempted()) {
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
        if (result.success()) {
            metricsRecorder.record(RabbitPublishCompensationOutcome.succeeded(RabbitPublishFailurePhase.NOTIFICATION));
            LOGGER.info(
                "Notification publish compensation completed eventId={} eventKey={} operation=notification_publish_compensation result=published retryCount={}",
                event.getId(),
                event.getEventKey(),
                event.getRetryCount()
            );
            return;
        }
        metricsRecorder.record(RabbitPublishCompensationOutcome.failed(
            RabbitPublishFailurePhase.NOTIFICATION,
            result.failureReason()
        ));
        LOGGER.warn(
            "Notification publish compensation failed eventId={} eventKey={} operation=notification_publish_compensation result=publish_failed retryCount={} reason={}",
            event.getId(),
            event.getEventKey(),
            event.getRetryCount(),
            result.failureReason()
        );
    }
}
