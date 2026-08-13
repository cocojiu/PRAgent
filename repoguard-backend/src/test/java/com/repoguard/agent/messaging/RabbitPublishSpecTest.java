package com.repoguard.agent.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.config.RabbitNotificationQueueProperties;
import org.junit.jupiter.api.Test;

class RabbitPublishSpecTest {

    @Test
    void fromBuildsSpecFromSharedPublishProperties() {
        RabbitNotificationQueueProperties properties = new RabbitNotificationQueueProperties();
        properties.setExchange("test.exchange");
        properties.setRoutingKey("test.created");
        properties.setPublishConfirmTimeoutMs(0);

        RabbitPublishSpec spec = RabbitPublishSpec.from(properties, "notification-event-7");

        assertThat(spec.exchange()).isEqualTo("test.exchange");
        assertThat(spec.routingKey()).isEqualTo("test.created");
        assertThat(spec.correlationIdPrefix()).isEqualTo("notification-event-7");
        assertThat(spec.normalizedConfirmTimeoutMs()).isEqualTo(1);
    }
}
