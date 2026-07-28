package com.repoguard.agent.notification;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.repoguard.agent.config.RabbitNotificationQueueProperties;
import com.repoguard.agent.entity.NotificationEvent;
import com.repoguard.agent.mapper.NotificationEventMapper;
import com.repoguard.agent.messaging.MessagePublishException;
import com.repoguard.agent.messaging.RabbitPublishCompensationPolicy;
import com.repoguard.agent.messaging.RabbitPublishCompensationMetricsRecorder;
import com.repoguard.agent.messaging.RabbitPublishCompensationSettingsFactory;
import com.repoguard.agent.messaging.RabbitPublishFailureClassifier;
import com.repoguard.agent.observability.RepoGuardMetrics;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class NotificationEventPublishCompensatorTest {

    private final NotificationEventMapper eventMapper = org.mockito.Mockito.mock(NotificationEventMapper.class);
    private final NotificationEventPublisher eventPublisher = org.mockito.Mockito.mock(NotificationEventPublisher.class);
    private final RepoGuardMetrics metrics = org.mockito.Mockito.mock(RepoGuardMetrics.class);
    private final RabbitPublishCompensationMetricsRecorder metricsRecorder =
        new RabbitPublishCompensationMetricsRecorder(metrics);
    private final RabbitNotificationQueueProperties properties = new RabbitNotificationQueueProperties();
    private final RabbitPublishCompensationPolicy compensationPolicy = new RabbitPublishCompensationPolicy();
    private final RabbitPublishCompensationSettingsFactory settingsFactory =
        new RabbitPublishCompensationSettingsFactory(compensationPolicy);
    private final NotificationOutboxEventStore outboxEventStore = new NotificationOutboxEventStore(eventMapper);
    private final NotificationPublishCompensationQuery compensationQuery = new NotificationPublishCompensationQuery(
        outboxEventStore,
        properties,
        settingsFactory
    );
    private final NotificationEventPublishCoordinator publishCoordinator = new NotificationEventPublishCoordinator(
        eventPublisher,
        properties,
        new NotificationPublishFailurePolicy(
            new NotificationRetrySchedule(),
            new NotificationTextLimiter(),
            compensationPolicy
        ),
        new NotificationPublishEventStateUpdater(eventMapper),
        new RabbitPublishFailureClassifier(),
        outboxEventStore,
        compensationQuery,
        new NotificationPublishExecutor(Runnable::run),
        "test-node"
    );

    @Test
    void compensatesDeliveryFailedEventsAfterClaimingPublishLease() {
        NotificationEvent event = event();
        when(eventMapper.selectList(any())).thenReturn(List.of(event));
        when(eventMapper.update(any(UpdateWrapper.class))).thenReturn(1);

        compensator().compensate();

        verify(eventPublisher).publishOnce(any(NotificationEventMessage.class));
        verify(eventMapper, org.mockito.Mockito.times(2)).update(any(UpdateWrapper.class));
        verify(metrics).rabbitPublishCompensationSucceeded("notification");
    }

    @Test
    void skipsPublishWhenClaimFenceIsLost() {
        NotificationEvent event = event();
        when(eventMapper.selectList(any())).thenReturn(List.of(event));
        when(eventMapper.update(any(UpdateWrapper.class))).thenReturn(0);

        compensator().compensate();

        verify(eventPublisher, never()).publishOnce(any(NotificationEventMessage.class));
        verify(metrics, never()).rabbitPublishCompensationSucceeded("notification");
        verify(metrics, never()).rabbitPublishCompensationFailed(any(), any());
    }

    @Test
    void recordsFailedCompensationMetricWhenPublishStillFails() {
        NotificationEvent event = event();
        when(eventMapper.selectList(any())).thenReturn(List.of(event));
        when(eventMapper.update(any(UpdateWrapper.class))).thenReturn(1);
        doThrow(new MessagePublishException("publisher confirm timed out"))
            .when(eventPublisher)
            .publishOnce(any(NotificationEventMessage.class));

        compensator().compensate();

        verify(metrics).rabbitPublishCompensationFailed("notification", "confirm_timeout");
    }

    private NotificationEventPublishCompensator compensator() {
        return new NotificationEventPublishCompensator(
            compensationQuery,
            publishCoordinator,
            metricsRecorder
        );
    }

    private NotificationEvent event() {
        NotificationEvent event = new NotificationEvent();
        event.setId(7L);
        event.setEventKey("REVIEW_FAILED:7");
        event.setEventType(NotificationEventType.REVIEW_FAILED.code());
        event.setTaskId(7L);
        event.setStatus(NotificationEventStatus.DELIVERY_FAILED.code());
        event.setRetryCount(1);
        event.setNextRetryAt(LocalDateTime.now().minusMinutes(1));
        return event;
    }
}
