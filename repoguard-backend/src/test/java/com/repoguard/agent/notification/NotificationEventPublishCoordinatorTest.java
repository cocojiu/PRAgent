package com.repoguard.agent.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.repoguard.agent.config.RabbitNotificationQueueProperties;
import com.repoguard.agent.entity.NotificationEvent;
import com.repoguard.agent.mapper.NotificationEventMapper;
import com.repoguard.agent.messaging.MessagePublishException;
import com.repoguard.agent.messaging.RabbitPublishCompensationPolicy;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class NotificationEventPublishCoordinatorTest {

    private final NotificationEventMapper eventMapper = org.mockito.Mockito.mock(NotificationEventMapper.class);
    private final NotificationEventPublisher publisher = org.mockito.Mockito.mock(NotificationEventPublisher.class);
    private final RabbitNotificationQueueProperties properties = new RabbitNotificationQueueProperties();
    private final NotificationEventPublishCoordinator coordinator = new NotificationEventPublishCoordinator(
        publisher,
        properties,
        new NotificationPublishFailurePolicy(
            new NotificationRetrySchedule(),
            new NotificationTextLimiter(),
            new RabbitPublishCompensationPolicy()
        ),
        new NotificationPublishEventStateUpdater(eventMapper)
    );

    @AfterEach
    void clearTransactionSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void publishesMessageAndMarksEventPublishedImmediatelyWithoutTransaction() {
        NotificationEvent event = event();

        coordinator.publishAfterCommit(event);

        ArgumentCaptor<NotificationEventMessage> messageCaptor =
            ArgumentCaptor.forClass(NotificationEventMessage.class);
        verify(publisher).publish(messageCaptor.capture());
        assertThat(messageCaptor.getValue().eventId()).isEqualTo(99L);
        assertThat(messageCaptor.getValue().eventKey()).isEqualTo("REVIEW_COMPLETED:42");
        verify(eventMapper).update(any(UpdateWrapper.class));
    }

    @Test
    void defersPublishUntilTransactionCommitWhenSynchronizationIsActive() {
        TransactionSynchronizationManager.initSynchronization();
        NotificationEvent event = event();

        coordinator.publishAfterCommit(event);

        verify(publisher, never()).publish(any(NotificationEventMessage.class));
        List<TransactionSynchronization> synchronizations =
            TransactionSynchronizationManager.getSynchronizations();
        assertThat(synchronizations).hasSize(1);

        synchronizations.get(0).afterCommit();

        verify(publisher).publish(any(NotificationEventMessage.class));
    }

    @Test
    void publishFailureMarksEventForCompensation() {
        properties.setPublishCompensationMaxAttempts(5);
        doThrow(new MessagePublishException("confirm timed out"))
            .when(publisher)
            .publish(any(NotificationEventMessage.class));

        coordinator.publish(event());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<UpdateWrapper<NotificationEvent>> wrapperCaptor = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(eventMapper).update(wrapperCaptor.capture());
        assertThat(wrapperCaptor.getValue().getSqlSet())
            .contains("status", "retry_count", "next_retry_at", "last_error", "publish_claimed_at", "publish_claimed_by");
    }

    private NotificationEvent event() {
        NotificationEvent event = new NotificationEvent();
        event.setId(99L);
        event.setEventKey("REVIEW_COMPLETED:42");
        event.setEventType(NotificationEventType.REVIEW_COMPLETED.code());
        event.setTaskId(42L);
        event.setBatchId(7L);
        event.setRetryCount(0);
        return event;
    }
}
