package com.repoguard.agent.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;

class RabbitMqConfigTest {

    private final RabbitMqConfig config = new RabbitMqConfig();

    @Test
    void defaultsUseVersionedReviewTopology() {
        RabbitReviewQueueProperties properties = new RabbitReviewQueueProperties();

        assertThat(properties.getExchange()).isEqualTo("repoguard.review.exchange.v2");
        assertThat(properties.getQueue()).isEqualTo("repoguard.review.queue.v2");
        assertThat(properties.getRoutingKey()).isEqualTo("repoguard.review.created.v2");
    }

    @Test
    void reviewQueueDeclaresDeadLetterArguments() {
        RabbitReviewQueueProperties properties = properties();

        Queue queue = config.reviewQueue(properties);

        assertThat(queue.getName()).isEqualTo("test.review.queue");
        assertThat(queue.isDurable()).isTrue();
        assertThat(queue.getArguments())
            .containsEntry("x-dead-letter-exchange", "test.review.dlx")
            .containsEntry("x-dead-letter-routing-key", "test.review.dead");
    }

    @Test
    void deadLetterTopologyUsesConfiguredNames() {
        RabbitReviewQueueProperties properties = properties();

        DirectExchange deadLetterExchange = config.reviewDeadLetterExchange(properties);
        Queue deadLetterQueue = config.reviewDeadLetterQueue(properties);
        Binding binding = config.reviewDeadLetterBinding(deadLetterQueue, deadLetterExchange, properties);

        assertThat(deadLetterExchange.getName()).isEqualTo("test.review.dlx");
        assertThat(deadLetterExchange.isDurable()).isTrue();
        assertThat(deadLetterQueue.getName()).isEqualTo("test.review.dlq");
        assertThat(deadLetterQueue.isDurable()).isTrue();
        assertThat(binding.getExchange()).isEqualTo("test.review.dlx");
        assertThat(binding.getRoutingKey()).isEqualTo("test.review.dead");
        assertThat(binding.getDestination()).isEqualTo("test.review.dlq");
    }

    private RabbitReviewQueueProperties properties() {
        RabbitReviewQueueProperties properties = new RabbitReviewQueueProperties();
        properties.setExchange("test.review.exchange");
        properties.setQueue("test.review.queue");
        properties.setRoutingKey("test.review.created");
        properties.setDeadLetterExchange("test.review.dlx");
        properties.setDeadLetterQueue("test.review.dlq");
        properties.setDeadLetterRoutingKey("test.review.dead");
        return properties;
    }
}
