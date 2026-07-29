package com.repoguard.agent.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.entity.NotificationEvent;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.NotificationEventMapper;
import com.repoguard.agent.notification.dispatch.NotificationCounterNormalizer;
import com.repoguard.agent.notification.dispatch.NotificationDispatchRequestFactory;
import com.repoguard.agent.notification.outbox.NotificationEventKeyFactory;
import com.repoguard.agent.notification.outbox.NotificationEventPayloadBuilder;
import com.repoguard.agent.notification.outbox.NotificationMessageJsonSerializer;
import com.repoguard.agent.notification.outbox.NotificationOutboxEventStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class NotificationDispatchServiceImplTest {

    private final NotificationEventMapper eventMapper = org.mockito.Mockito.mock(NotificationEventMapper.class);
    private final NotificationOutboxEventStore outboxEventStore = new NotificationOutboxEventStore(eventMapper);
    private final NotificationEventPayloadBuilder payloadBuilder =
        new NotificationEventPayloadBuilder(
            new NotificationEventKeyFactory(),
            new NotificationMessageJsonSerializer(new com.fasterxml.jackson.databind.ObjectMapper())
        );
    private final NotificationEventPublishCoordinator publishCoordinator =
        org.mockito.Mockito.mock(NotificationEventPublishCoordinator.class);
    private final NotificationDispatchServiceImpl service = new NotificationDispatchServiceImpl(
        outboxEventStore,
        payloadBuilder,
        publishCoordinator,
        new NotificationDispatchRequestFactory(new NotificationCounterNormalizer())
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
        verify(publishCoordinator).publishAfterCommit(eventCaptor.getValue());
    }

    @Test
    void publishExistingEventSkipsAlreadyPublishedEvent() {
        NotificationEvent event = new NotificationEvent();
        event.setId(99L);
        event.setStatus("PUBLISHED");
        when(eventMapper.selectById(99L)).thenReturn(event);

        service.publishExistingEvent(99L);

        verify(publishCoordinator, never()).publishAfterCommit(any(NotificationEvent.class));
    }

    @Test
    void publishExistingEventPublishesPendingEventAfterCommit() {
        NotificationEvent event = new NotificationEvent();
        event.setId(99L);
        event.setStatus(NotificationEventStatus.PENDING.code());
        when(eventMapper.selectById(99L)).thenReturn(event);

        service.publishExistingEvent(99L);

        verify(publishCoordinator).publishAfterCommit(event);
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
