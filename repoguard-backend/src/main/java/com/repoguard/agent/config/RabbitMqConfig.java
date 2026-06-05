package com.repoguard.agent.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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
    public Queue reviewQueue(RabbitReviewQueueProperties properties) {
        return new Queue(properties.getQueue(), true);
    }

    @Bean
    public Binding reviewBinding(
        Queue reviewQueue,
        DirectExchange reviewExchange,
        RabbitReviewQueueProperties properties
    ) {
        return BindingBuilder.bind(reviewQueue).to(reviewExchange).with(properties.getRoutingKey());
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new JacksonJsonMessageConverter();
    }
}
