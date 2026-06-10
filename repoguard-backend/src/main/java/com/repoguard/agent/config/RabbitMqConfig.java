package com.repoguard.agent.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(RabbitReviewQueueProperties.class)
public class RabbitMqConfig {

    @Bean
    public DirectExchange reviewExchange(RabbitReviewQueueProperties properties) {
        return new DirectExchange(properties.getExchange(), true, false);
    }

    @Bean
    public DirectExchange reviewDeadLetterExchange(RabbitReviewQueueProperties properties) {
        return new DirectExchange(properties.getDeadLetterExchange(), true, false);
    }

    @Bean
    public Queue reviewQueue(RabbitReviewQueueProperties properties) {
        return QueueBuilder.durable(properties.getQueue())
            .withArgument("x-dead-letter-exchange", properties.getDeadLetterExchange())
            .withArgument("x-dead-letter-routing-key", properties.getDeadLetterRoutingKey())
            .build();
    }

    @Bean
    public Queue reviewDeadLetterQueue(RabbitReviewQueueProperties properties) {
        return QueueBuilder.durable(properties.getDeadLetterQueue()).build();
    }

    @Bean
    public Binding reviewBinding(
        @Qualifier("reviewQueue") Queue reviewQueue,
        @Qualifier("reviewExchange") DirectExchange reviewExchange,
        RabbitReviewQueueProperties properties
    ) {
        return BindingBuilder.bind(reviewQueue).to(reviewExchange).with(properties.getRoutingKey());
    }

    @Bean
    public Binding reviewDeadLetterBinding(
        @Qualifier("reviewDeadLetterQueue") Queue reviewDeadLetterQueue,
        @Qualifier("reviewDeadLetterExchange") DirectExchange reviewDeadLetterExchange,
        RabbitReviewQueueProperties properties
    ) {
        return BindingBuilder
            .bind(reviewDeadLetterQueue)
            .to(reviewDeadLetterExchange)
            .with(properties.getDeadLetterRoutingKey());
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new JacksonJsonMessageConverter();
    }
}
