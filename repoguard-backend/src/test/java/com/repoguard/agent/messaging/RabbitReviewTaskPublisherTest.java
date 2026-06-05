package com.repoguard.agent.messaging;

import static org.mockito.Mockito.verify;

import com.repoguard.agent.config.RabbitReviewQueueProperties;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class RabbitReviewTaskPublisherTest {

    @Test
    void publishSendsMessageToConfiguredExchangeAndRoutingKey() {
        RabbitTemplate rabbitTemplate = org.mockito.Mockito.mock(RabbitTemplate.class);
        RabbitReviewQueueProperties properties = new RabbitReviewQueueProperties();
        properties.setExchange("test.review.exchange");
        properties.setRoutingKey("test.review.created");
        ReviewTaskMessage message = new ReviewTaskMessage(
            42L,
            "repo-guard-demo",
            "spring-boot-demo",
            512,
            "a1b2c3d",
            LocalDateTime.parse("2026-06-05T17:00:00")
        );

        new RabbitReviewTaskPublisher(rabbitTemplate, properties).publish(message);

        verify(rabbitTemplate).convertAndSend("test.review.exchange", "test.review.created", message);
    }
}
