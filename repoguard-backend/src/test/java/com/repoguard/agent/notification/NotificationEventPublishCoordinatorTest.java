package com.repoguard.agent.notification;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.repoguard.agent.messaging.RabbitPublishCompensationSettingsFactory;
import com.repoguard.agent.messaging.RabbitPublishFailureClassifier;
import com.repoguard.agent.notification.outbox.NotificationOutboxEventStore;
import com.repoguard.agent.notification.outbox.NotificationPublishCompensationQuery;
import com.repoguard.agent.notification.outbox.NotificationPublishEventStateUpdater;
import com.repoguard.agent.notification.retry.NotificationRetrySchedule;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class NotificationEventPublishCoordinatorTest {

    private final NotificationEventMapper eventMapper = org.mockito.Mockito.mock(NotificationEventMapper.class);
    private final NotificationEventPublisher publisher = org.mockito.Mockito.mock(NotificationEventPublisher.class);
    private final RabbitNotificationQueueProperties properties = new RabbitNotificationQueueProperties();
    private final RabbitPublishCompensationPolicy compensationPolicy = new RabbitPublishCompensationPolicy();
    private final NotificationOutboxEventStore outboxEventStore = new NotificationOutboxEventStore(eventMapper);
    private final NotificationPublishCompensationQuery compensationQuery = new NotificationPublishCompensationQuery(
        outboxEventStore,
        properties,
        new RabbitPublishCompensationSettingsFactory(compensationPolicy)
    );
    private final NotificationEventPublishCoordinator coordinator = coordinator(Runnable::run);

    @AfterEach
    void clearTransactionSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void publishesMessageAndMarksEventPublishedImmediatelyWithoutTransaction() {
        NotificationEvent event = event();
        when(eventMapper.update(any(UpdateWrapper.class))).thenReturn(1);

        coordinator.publishAfterCommit(event);

        ArgumentCaptor<NotificationEventMessage> messageCaptor =
            ArgumentCaptor.forClass(NotificationEventMessage.class);
        verify(publisher).publishOnce(messageCaptor.capture());
        assertThat(messageCaptor.getValue().eventId()).isEqualTo(99L);
        assertThat(messageCaptor.getValue().eventKey()).isEqualTo("REVIEW_COMPLETED:42");
        verify(eventMapper, org.mockito.Mockito.times(2)).update(any(UpdateWrapper.class));
        assertThat(event.getStatus()).isEqualTo(NotificationEventStatus.PUBLISHED.code());
    }

    @Test
    void afterCommitOnlySubmitsPublishWorkToExecutor() {
        TransactionSynchronizationManager.initSynchronization();
        NotificationEvent event = event();
        when(eventMapper.update(any(UpdateWrapper.class))).thenReturn(1);

        coordinator.publishAfterCommit(event);

        verify(publisher, never()).publishOnce(any(NotificationEventMessage.class));
        List<TransactionSynchronization> synchronizations =
            TransactionSynchronizationManager.getSynchronizations();
        assertThat(synchronizations).hasSize(1);

        synchronizations.get(0).afterCommit();

        verify(publisher).publishOnce(any(NotificationEventMessage.class));
    }

    @Test
    void executorRejectionLeavesOutboxPendingForCompensation() {
        NotificationEvent event = event();
        NotificationEventPublishCoordinator rejectingCoordinator = coordinator(command -> {
            throw new RejectedExecutionException("queue full");
        });

        rejectingCoordinator.publishAfterCommit(event);

        verify(publisher, never()).publishOnce(any(NotificationEventMessage.class));
        verify(eventMapper, never()).update(any(UpdateWrapper.class));
        assertThat(event.getStatus()).isEqualTo(NotificationEventStatus.PENDING.code());
    }

    @Test
    void publishFailureMarksEventForCompensation() {
        properties.setPublishCompensationMaxAttempts(5);
        when(eventMapper.update(any(UpdateWrapper.class))).thenReturn(1);
        doThrow(new MessagePublishException("confirm timed out"))
            .when(publisher)
            .publishOnce(any(NotificationEventMessage.class));

        NotificationPublishResult result = coordinator.publish(event());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<UpdateWrapper<NotificationEvent>> wrapperCaptor = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(eventMapper, org.mockito.Mockito.times(2)).update(wrapperCaptor.capture());
        assertThat(wrapperCaptor.getAllValues().getLast().getSqlSet())
            .contains("status", "retry_count", "next_retry_at", "last_error", "publish_claimed_at", "publish_claimed_by");
        assertThat(result.attempted()).isTrue();
        assertThat(result.success()).isFalse();
        assertThat(result.failureReason()).isEqualTo("confirm_timeout");
    }

    private NotificationEventPublishCoordinator coordinator(java.util.concurrent.Executor executor) {
        return new NotificationEventPublishCoordinator(
            publisher,
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
            new NotificationPublishExecutor(executor),
            "test-node"
        );
    }

    private NotificationEvent event() {
        NotificationEvent event = new NotificationEvent();
        event.setId(99L);
        event.setEventKey("REVIEW_COMPLETED:42");
        event.setEventType(NotificationEventType.REVIEW_COMPLETED.code());
        event.setTaskId(42L);
        event.setBatchId(7L);
        event.setStatus(NotificationEventStatus.PENDING.code());
        event.setRetryCount(0);
        event.setNextRetryAt(java.time.LocalDateTime.now().minusSeconds(1));
        return event;
    }
}
