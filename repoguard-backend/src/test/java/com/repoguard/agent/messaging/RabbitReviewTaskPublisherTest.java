package com.repoguard.agent.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.repoguard.agent.config.RabbitReviewQueueProperties;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class RabbitReviewTaskPublisherTest {

    @Test
    void publishSendsMessageToConfiguredExchangeAndRoutingKeyAndWaitsForAck() {
        RabbitTemplate rabbitTemplate = org.mockito.Mockito.mock(RabbitTemplate.class);
        RabbitReviewQueueProperties properties = properties();
        ReviewTaskMessage message = message();
        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(3);
            correlationData.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).convertAndSend(
            eq("test.review.exchange"),
            eq("test.review.created"),
            eq(message),
            any(CorrelationData.class)
        );

        new RabbitReviewTaskPublisher(rabbitTemplate, properties).publish(message);

        ArgumentCaptor<CorrelationData> correlationCaptor = ArgumentCaptor.forClass(CorrelationData.class);
        verify(rabbitTemplate).convertAndSend(
            eq("test.review.exchange"),
            eq("test.review.created"),
            eq(message),
            correlationCaptor.capture()
        );
        assertThat(correlationCaptor.getValue().getId()).contains("review-task-42-attempt-1");
    }

    @Test
    void publishRetriesAndFailsWhenPublisherConfirmIsNacked() {
        RabbitTemplate rabbitTemplate = org.mockito.Mockito.mock(RabbitTemplate.class);
        RabbitReviewQueueProperties properties = properties();
        ReviewTaskMessage message = message();
        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(3);
            correlationData.getFuture().complete(new CorrelationData.Confirm(false, "broker refused"));
            return null;
        }).when(rabbitTemplate).convertAndSend(any(String.class), any(String.class), eq(message), any(CorrelationData.class));

        RabbitReviewTaskPublisher publisher = new RabbitReviewTaskPublisher(rabbitTemplate, properties);

        assertThatThrownBy(() -> publisher.publish(message))
            .isInstanceOf(MessagePublishException.class)
            .hasMessageContaining("nacked");
        verify(rabbitTemplate, times(3))
            .convertAndSend(eq("test.review.exchange"), eq("test.review.created"), eq(message), any(CorrelationData.class));
    }

    @Test
    void publishRetriesAndFailsWhenMessageIsReturned() {
        RabbitTemplate rabbitTemplate = org.mockito.Mockito.mock(RabbitTemplate.class);
        RabbitReviewQueueProperties properties = properties();
        ReviewTaskMessage message = message();
        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(3);
            correlationData.setReturned(new ReturnedMessage(
                new Message(new byte[0], new MessageProperties()),
                312,
                "NO_ROUTE",
                "test.review.exchange",
                "test.review.created"
            ));
            correlationData.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).convertAndSend(any(String.class), any(String.class), eq(message), any(CorrelationData.class));

        RabbitReviewTaskPublisher publisher = new RabbitReviewTaskPublisher(rabbitTemplate, properties);

        assertThatThrownBy(() -> publisher.publish(message))
            .isInstanceOf(MessagePublishException.class)
            .hasMessageContaining("unroutable");
        verify(rabbitTemplate, times(3))
            .convertAndSend(eq("test.review.exchange"), eq("test.review.created"), eq(message), any(CorrelationData.class));
    }

    @Test
    void publishRetriesAndFailsWhenSendThrowsAmqpException() {
        RabbitTemplate rabbitTemplate = org.mockito.Mockito.mock(RabbitTemplate.class);
        RabbitReviewQueueProperties properties = properties();
        ReviewTaskMessage message = message();
        doThrow(new AmqpConnectException(new RuntimeException("connection refused")))
            .when(rabbitTemplate)
            .convertAndSend(any(String.class), any(String.class), eq(message), any(CorrelationData.class));

        RabbitReviewTaskPublisher publisher = new RabbitReviewTaskPublisher(rabbitTemplate, properties);

        assertThatThrownBy(() -> publisher.publish(message))
            .isInstanceOf(MessagePublishException.class)
            .hasMessageContaining("publish attempt failed");
        verify(rabbitTemplate, times(3))
            .convertAndSend(eq("test.review.exchange"), eq("test.review.created"), eq(message), any(CorrelationData.class));
    }

    private RabbitReviewQueueProperties properties() {
        RabbitReviewQueueProperties properties = new RabbitReviewQueueProperties();
        properties.setExchange("test.review.exchange");
        properties.setRoutingKey("test.review.created");
        properties.setPublishMaxAttempts(3);
        properties.setPublishInitialIntervalMs(0);
        properties.setPublishConfirmTimeoutMs(100);
        return properties;
    }

    private ReviewTaskMessage message() {
        return new ReviewTaskMessage(
            42L,
            "repo-guard-demo",
            "spring-boot-demo",
            512,
            "a1b2c3d",
            LocalDateTime.parse("2026-06-05T17:00:00")
        );
    }
}
