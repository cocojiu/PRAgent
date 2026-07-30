package com.repoguard.agent.notification.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.config.RabbitNotificationQueueProperties;
import com.repoguard.agent.entity.NotificationEvent;
import com.repoguard.agent.messaging.RabbitPublishCompensationPolicy;
import com.repoguard.agent.messaging.RabbitPublishCompensationSettingsFactory;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class NotificationPublishCompensationQueryTest {

    private final NotificationOutboxEventStore outboxEventStore =
        org.mockito.Mockito.mock(NotificationOutboxEventStore.class);
    private final RabbitNotificationQueueProperties properties = new RabbitNotificationQueueProperties();
    private final RabbitPublishCompensationSettingsFactory settingsFactory =
        new RabbitPublishCompensationSettingsFactory(new RabbitPublishCompensationPolicy());
    private final NotificationPublishCompensationQuery query =
        new NotificationPublishCompensationQuery(outboxEventStore, properties, settingsFactory);

    @Test
    void constructorRejectsMissingSettingsFactory() {
        assertThatThrownBy(() -> new NotificationPublishCompensationQuery(outboxEventStore, properties, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("settingsFactory");
    }

    @Test
    void loadsDueEventsThroughClaimAwareOutboxStore() {
        NotificationEvent event = new NotificationEvent();
        event.setId(7L);
        when(outboxEventStore.loadDuePublishEvents(any(), any(), anyInt(), anyInt()))
            .thenReturn(List.of(event));

        LocalDateTime now = LocalDateTime.now();
        List<NotificationEvent> result = query.loadDueEvents(now);

        assertThat(result).containsExactly(event);
        verify(outboxEventStore).loadDuePublishEvents(
            org.mockito.Mockito.eq(now),
            any(LocalDateTime.class),
            org.mockito.Mockito.eq(5),
            org.mockito.Mockito.eq(20)
        );
    }

    @Test
    void usesMinimumLimitsForInvalidCompensationProperties() {
        properties.setPublishCompensationMaxAttempts(0);
        properties.setPublishCompensationBatchSize(0);

        assertThat(query.maxAttempts()).isOne();
        assertThat(query.batchSize()).isOne();
    }
}
