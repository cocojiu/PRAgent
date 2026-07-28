package com.repoguard.agent.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.entity.NotificationEvent;
import com.repoguard.agent.mapper.NotificationEventMapper;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class NotificationDeliverableEventQueryTest {

    private final NotificationEventMapper eventMapper =
        org.mockito.Mockito.mock(NotificationEventMapper.class);
    private final NotificationDeliverableEventQuery query =
        new NotificationDeliverableEventQuery(eventMapper);

    @Test
    void returnsPublishedEventForDelivery() {
        NotificationEvent event = event("PUBLISHED");
        when(eventMapper.selectById(11L)).thenReturn(event);

        Optional<NotificationEvent> result = query.load(11L);

        assertThat(result).containsSame(event);
        verify(eventMapper).selectById(11L);
    }

    @Test
    void returnsPublishingEventSoFastConsumerCannotLoseMessage() {
        NotificationEvent event = event("PUBLISHING");
        when(eventMapper.selectById(11L)).thenReturn(event);

        assertThat(query.load(11L)).containsSame(event);
    }

    @Test
    void skipsUnknownStatusEvent() {
        NotificationEvent event = event("CUSTOM_STATUS");
        when(eventMapper.selectById(11L)).thenReturn(event);

        Optional<NotificationEvent> result = query.load(11L);

        assertThat(result).isEmpty();
    }

    @Test
    void skipsMissingEvent() {
        when(eventMapper.selectById(11L)).thenReturn(null);

        assertThat(query.load(11L)).isEmpty();
    }

    @Test
    void skipsAlreadyDeliveredEvent() {
        when(eventMapper.selectById(11L)).thenReturn(event("DELIVERED"));

        assertThat(query.load(11L)).isEmpty();
    }

    @Test
    void skipsDeadEvent() {
        when(eventMapper.selectById(11L)).thenReturn(event("DEAD"));

        assertThat(query.load(11L)).isEmpty();
    }

    private NotificationEvent event(String status) {
        NotificationEvent event = new NotificationEvent();
        event.setId(11L);
        event.setStatus(status);
        return event;
    }
}
