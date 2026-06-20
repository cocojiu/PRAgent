package com.repoguard.agent.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.repoguard.agent.config.RabbitNotificationQueueProperties;
import com.repoguard.agent.entity.NotificationEvent;
import com.repoguard.agent.mapper.NotificationEventMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class NotificationPublishCompensationQueryTest {

    private final NotificationEventMapper eventMapper =
        org.mockito.Mockito.mock(NotificationEventMapper.class);
    private final RabbitNotificationQueueProperties properties = new RabbitNotificationQueueProperties();
    private final NotificationPublishCompensationQuery query =
        new NotificationPublishCompensationQuery(eventMapper, properties);

    @Test
    void loadsDueEventsWithCompensationQueryRules() {
        NotificationEvent event = event(7L);
        when(eventMapper.selectList(any())).thenReturn(List.of(event));

        List<NotificationEvent> result = query.loadDueEvents();

        assertThat(result).containsExactly(event);
        org.mockito.Mockito.verify(eventMapper).selectList(any());
    }

    @Test
    void usesMinimumLimitsForInvalidCompensationProperties() {
        properties.setPublishCompensationMaxAttempts(0);
        properties.setPublishCompensationBatchSize(0);

        assertThat(query.maxAttempts()).isOne();
        assertThat(query.batchSize()).isOne();
    }

    private NotificationEvent event(Long id) {
        NotificationEvent event = new NotificationEvent();
        event.setId(id);
        return event;
    }
}
