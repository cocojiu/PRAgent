package com.repoguard.agent.notification.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.repoguard.agent.entity.NotificationEvent;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.NotificationEventMapper;
import com.repoguard.agent.messaging.RabbitPublishClaim;
import com.repoguard.agent.notification.NotificationEventStatus;
import com.repoguard.agent.notification.NotificationEventType;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

class NotificationOutboxEventStoreTest {

    private final NotificationEventMapper eventMapper =
        org.mockito.Mockito.mock(NotificationEventMapper.class);
    private final NotificationOutboxEventStore store = new NotificationOutboxEventStore(eventMapper);

    @Test
    void constructorRejectsMissingEventMapper() {
        assertThatThrownBy(() -> new NotificationOutboxEventStore(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("eventMapper");
    }

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

    @Test
    void loadsDuePublishEventsWithClaimLeaseFilter() {
        NotificationEvent event = event(7L);
        when(eventMapper.selectList(any())).thenReturn(List.of(event));

        List<NotificationEvent> result = store.loadDuePublishEvents(
            LocalDateTime.now(),
            LocalDateTime.now().minusMinutes(2),
            5,
            20
        );

        assertThat(result).containsExactly(event);
        org.mockito.Mockito.verify(eventMapper).selectList(any());
    }

    @Test
    void claimsDuePublishEventAndUpdatesInMemoryFence() {
        NotificationEvent event = event(7L);
        LocalDateTime claimedAt = LocalDateTime.now();
        when(eventMapper.update(any())).thenReturn(1);

        boolean claimed = store.claimForPublish(
            event,
            new RabbitPublishClaim(claimedAt, "node-a", claimedAt.minusMinutes(2), 5)
        );

        assertThat(claimed).isTrue();
        assertThat(event.getPublishClaimedAt()).isEqualTo(claimedAt);
        assertThat(event.getPublishClaimedBy()).isEqualTo("node-a");
        assertThat(event.getStatus()).isEqualTo(NotificationEventStatus.PUBLISHING.code());
        assertThat(event.getUpdatedAt()).isEqualTo(claimedAt);
    }

    @Test
    void claimReturnsFalseWhenFenceIsLost() {
        NotificationEvent event = event(7L);
        LocalDateTime claimedAt = LocalDateTime.now();
        when(eventMapper.update(any())).thenReturn(0);

        assertThat(store.claimForPublish(event, new RabbitPublishClaim(claimedAt, "node-a", claimedAt.minusMinutes(2), 5)))
            .isFalse();
        assertThat(event.getPublishClaimedAt()).isNull();
        assertThat(event.getPublishClaimedBy()).isNull();
    }

    private NotificationEventPayload payload(String eventKey) {
        return new NotificationEventPayload(eventKey, null, "{\"eventType\":\"REVIEW_COMPLETED\"}");
    }

    private NotificationEvent event(Long id) {
        NotificationEvent event = new NotificationEvent();
        event.setId(id);
        event.setStatus(NotificationEventStatus.PUBLISH_FAILED.code());
        event.setRetryCount(1);
        event.setNextRetryAt(LocalDateTime.now().minusMinutes(1));
        return event;
    }

    private ReviewTask task() {
        ReviewTask task = new ReviewTask();
        task.setId(42L);
        return task;
    }
}
