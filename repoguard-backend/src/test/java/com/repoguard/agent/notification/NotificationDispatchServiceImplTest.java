package com.repoguard.agent.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.config.RabbitNotificationQueueProperties;
import com.repoguard.agent.entity.NotificationEvent;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.NotificationEventMapper;
import com.repoguard.agent.messaging.MessagePublishException;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class NotificationDispatchServiceImplTest {

    private final NotificationEventMapper eventMapper = org.mockito.Mockito.mock(NotificationEventMapper.class);
    private final NotificationOutboxEventStore outboxEventStore = new NotificationOutboxEventStore(eventMapper);
    private final NotificationEventPublisher publisher = org.mockito.Mockito.mock(NotificationEventPublisher.class);
    private final RabbitNotificationQueueProperties properties = new RabbitNotificationQueueProperties();
    private final NotificationEventPayloadBuilder payloadBuilder = new NotificationEventPayloadBuilder(new com.fasterxml.jackson.databind.ObjectMapper());
    private final NotificationPublishFailurePolicy publishFailurePolicy = new NotificationPublishFailurePolicy();
    private final NotificationPublishEventStateUpdater publishEventStateUpdater =
        new NotificationPublishEventStateUpdater(eventMapper);
    private final NotificationDispatchServiceImpl service = new NotificationDispatchServiceImpl(
        outboxEventStore,
        publisher,
        properties,
        payloadBuilder,
        publishFailurePolicy,
        publishEventStateUpdater
    );

    @Test
    void reviewFinishedCreatesOutboxEventAndPublishesMessage() {
        when(eventMapper.insert(any(NotificationEvent.class))).thenAnswer(invocation -> {
            NotificationEvent event = invocation.getArgument(0);
            event.setId(99L);
            return 1;
        });

        service.reviewFinished(task("COMPLETED"), 3);

        ArgumentCaptor<NotificationEvent> eventCaptor = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(eventMapper).insert(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getEventKey()).isEqualTo(NotificationEventType.REVIEW_COMPLETED.code() + ":42");
        assertThat(eventCaptor.getValue().getStatus()).isEqualTo(NotificationEventStatus.PENDING.code());
        assertThat(eventCaptor.getValue().getPayload()).contains("\"findingCount\":3");
        verify(publisher).publish(any(NotificationEventMessage.class));
    }

    @Test
    void publishFailureMarksEventPublishFailedForCompensation() {
        properties.setPublishCompensationMaxAttempts(5);
        when(eventMapper.insert(any(NotificationEvent.class))).thenAnswer(invocation -> {
            NotificationEvent event = invocation.getArgument(0);
            event.setId(99L);
            return 1;
        });
        doThrow(new MessagePublishException("confirm timed out")).when(publisher).publish(any(NotificationEventMessage.class));

        service.reviewFailed(task("FAILED"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<UpdateWrapper<NotificationEvent>> wrapperCaptor = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(eventMapper).update(wrapperCaptor.capture());
        assertThat(wrapperCaptor.getValue().getSqlSet()).contains("status", "retry_count", "next_retry_at", "last_error");
    }

    @Test
    void publishExistingEventSkipsAlreadyPublishedEvent() {
        NotificationEvent event = new NotificationEvent();
        event.setId(99L);
        event.setStatus("PUBLISHED");
        when(eventMapper.selectById(99L)).thenReturn(event);

        service.publishExistingEvent(99L);

        verify(publisher, never()).publish(any(NotificationEventMessage.class));
    }

    private ReviewTask task(String status) {
        ReviewTask task = new ReviewTask();
        task.setId(42L);
        task.setOrganization("octocat");
        task.setRepository("Hello-World");
        task.setPrNumber(7);
        task.setTitle("Improve review flow");
        task.setStatus(status);
        task.setRiskLevel("HIGH");
        task.setHumanReviewRequired(false);
        return task;
    }
}
