package com.repoguard.agent.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.repoguard.agent.entity.NotificationEvent;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.NotificationEventMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

class NotificationOutboxEventStoreTest {

    private final NotificationEventMapper eventMapper =
        org.mockito.Mockito.mock(NotificationEventMapper.class);
    private final NotificationOutboxEventStore store = new NotificationOutboxEventStore(eventMapper);

    @Test
    void createsPendingEventFromPayload() {
        when(eventMapper.insert(any(NotificationEvent.class))).thenAnswer(invocation -> {
            NotificationEvent event = invocation.getArgument(0);
            event.setId(99L);
            return 1;
        });

        NotificationEvent created = store.createPendingEvent(
            NotificationEventType.REVIEW_COMPLETED.code(),
            task(),
            null,
            payload("REVIEW_COMPLETED:42")
        );

        ArgumentCaptor<NotificationEvent> eventCaptor = ArgumentCaptor.forClass(NotificationEvent.class);
        org.mockito.Mockito.verify(eventMapper).insert(eventCaptor.capture());
        NotificationEvent inserted = eventCaptor.getValue();
        assertThat(created).isSameAs(inserted);
        assertThat(inserted.getId()).isEqualTo(99L);
        assertThat(inserted.getEventKey()).isEqualTo("REVIEW_COMPLETED:42");
        assertThat(inserted.getStatus()).isEqualTo(NotificationEventStatus.PENDING.code());
        assertThat(inserted.getRetryCount()).isZero();
        assertThat(inserted.getNextRetryAt()).isNotNull();
        assertThat(inserted.getCreatedAt()).isNotNull();
        assertThat(inserted.getUpdatedAt()).isNotNull();
    }

    @Test
    void duplicateEventKeyReturnsExistingEvent() {
        NotificationEvent existing = new NotificationEvent();
        existing.setId(100L);
        existing.setEventKey("REVIEW_COMPLETED:42");
        when(eventMapper.insert(any(NotificationEvent.class))).thenThrow(new DuplicateKeyException("duplicate"));
        when(eventMapper.selectOne(any())).thenReturn(existing);

        NotificationEvent created = store.createPendingEvent(
            NotificationEventType.REVIEW_COMPLETED.code(),
            task(),
            null,
            payload("REVIEW_COMPLETED:42")
        );

        assertThat(created).isSameAs(existing);
    }

    @Test
    void loadsEventById() {
        NotificationEvent event = new NotificationEvent();
        event.setId(99L);
        when(eventMapper.selectById(99L)).thenReturn(event);

        assertThat(store.loadById(99L)).isSameAs(event);
    }

    private NotificationEventPayload payload(String eventKey) {
        return new NotificationEventPayload(eventKey, null, "{\"eventType\":\"REVIEW_COMPLETED\"}");
    }

    private ReviewTask task() {
        ReviewTask task = new ReviewTask();
        task.setId(42L);
        return task;
    }
}
