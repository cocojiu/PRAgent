package com.repoguard.agent.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.repoguard.agent.config.RabbitNotificationQueueProperties;
import com.repoguard.agent.messaging.MessagePublishException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class RabbitNotificationEventPublisherTest {

    @Test
    void publishSendsMessageToConfiguredExchangeAndRoutingKeyAndWaitsForAck() {
        RabbitTemplate rabbitTemplate = org.mockito.Mockito.mock(RabbitTemplate.class);
        RabbitNotificationQueueProperties properties = properties();
        NotificationEventMessage message = message();
        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(3);
            correlationData.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).convertAndSend(
            eq("test.notification.exchange"),
            eq("test.notification.created"),
            eq(message),
            any(CorrelationData.class)
        );

        new RabbitNotificationEventPublisher(rabbitTemplate, properties).publish(message);

        ArgumentCaptor<CorrelationData> correlationCaptor = ArgumentCaptor.forClass(CorrelationData.class);
        verify(rabbitTemplate).convertAndSend(
            eq("test.notification.exchange"),
            eq("test.notification.created"),
            eq(message),
            correlationCaptor.capture()
        );
        assertThat(correlationCaptor.getValue().getId()).contains("notification-event-1001-attempt-1");
    }

    @Test
    void publishRetriesAndFailsWhenPublisherConfirmIsNacked() {
        RabbitTemplate rabbitTemplate = org.mockito.Mockito.mock(RabbitTemplate.class);
        RabbitNotificationQueueProperties properties = properties();
        NotificationEventMessage message = message();
        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(3);
            correlationData.getFuture().complete(new CorrelationData.Confirm(false, "broker refused"));
            return null;
        }).when(rabbitTemplate).convertAndSend(any(String.class), any(String.class), eq(message), any(CorrelationData.class));

        RabbitNotificationEventPublisher publisher = new RabbitNotificationEventPublisher(rabbitTemplate, properties);

        assertThatThrownBy(() -> publisher.publish(message))
            .isInstanceOf(MessagePublishException.class)
            .hasMessageContaining("nacked");
        verify(rabbitTemplate, times(3))
            .convertAndSend(eq("test.notification.exchange"), eq("test.notification.created"), eq(message), any(CorrelationData.class));
    }

    @Test
    void publishRetriesAndFailsWhenMessageIsReturned() {
        RabbitTemplate rabbitTemplate = org.mockito.Mockito.mock(RabbitTemplate.class);
        RabbitNotificationQueueProperties properties = properties();
        NotificationEventMessage message = message();
        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(3);
            correlationData.setReturned(new ReturnedMessage(
                new Message(new byte[0], new MessageProperties()),
                312,
                "NO_ROUTE",
                "test.notification.exchange",
                "test.notification.created"
            ));
            correlationData.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).convertAndSend(any(String.class), any(String.class), eq(message), any(CorrelationData.class));

        RabbitNotificationEventPublisher publisher = new RabbitNotificationEventPublisher(rabbitTemplate, properties);

        assertThatThrownBy(() -> publisher.publish(message))
            .isInstanceOf(MessagePublishException.class)
            .hasMessageContaining("unroutable");
        verify(rabbitTemplate, times(3))
            .convertAndSend(eq("test.notification.exchange"), eq("test.notification.created"), eq(message), any(CorrelationData.class));
    }

    private RabbitNotificationQueueProperties properties() {
        RabbitNotificationQueueProperties properties = new RabbitNotificationQueueProperties();
        properties.setExchange("test.notification.exchange");
        properties.setRoutingKey("test.notification.created");
        properties.setPublishMaxAttempts(3);
        properties.setPublishInitialIntervalMs(0);
        properties.setPublishConfirmTimeoutMs(100);
        return properties;
    }

    private NotificationEventMessage message() {
        return new NotificationEventMessage(1001L, "review.completed:42", "REVIEW_COMPLETED", 42L, null);
    }
}
