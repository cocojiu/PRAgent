package com.repoguard.agent.messaging;

import com.repoguard.agent.config.RabbitReviewQueueProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class RabbitReviewTaskPublisher implements ReviewTaskPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final RabbitReviewQueueProperties properties;

    public RabbitReviewTaskPublisher(RabbitTemplate rabbitTemplate, RabbitReviewQueueProperties properties) {
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
    }

    @Override
    public void publish(ReviewTaskMessage message) {
        rabbitTemplate.convertAndSend(properties.getExchange(), properties.getRoutingKey(), message);
    }
}
