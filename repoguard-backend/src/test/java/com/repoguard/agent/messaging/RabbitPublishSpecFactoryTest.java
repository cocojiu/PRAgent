package com.repoguard.agent.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.config.RabbitNotificationQueueProperties;
import org.junit.jupiter.api.Test;

class RabbitPublishSpecFactoryTest {

    private final RabbitPublishSpecFactory factory = new RabbitPublishSpecFactory();

    @Test
    void createsReviewTaskSpecWithStableCorrelationPrefix() {
        RabbitPublishSpec spec = factory.reviewTask(properties(), 42L);

        assertThat(spec.correlationIdPrefix()).isEqualTo("review-task-42");
    }

    @Test
    void createsNotificationEventSpecWithStableCorrelationPrefix() {
        RabbitPublishSpec spec = factory.notificationEvent(properties(), 1001L);

        assertThat(spec.correlationIdPrefix()).isEqualTo("notification-event-1001");
    }

    private RabbitNotificationQueueProperties properties() {
        RabbitNotificationQueueProperties properties = new RabbitNotificationQueueProperties();
        properties.setExchange("test.exchange");
        properties.setRoutingKey("test.created");
        return properties;
    }
}
