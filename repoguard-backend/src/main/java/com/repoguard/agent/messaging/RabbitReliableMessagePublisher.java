package com.repoguard.agent.messaging;

import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.Objects;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class RabbitReliableMessagePublisher {

    private final RabbitTemplate rabbitTemplate;
    private final RabbitPublishFailureClassifier failureClassifier;

    public RabbitReliableMessagePublisher(RabbitTemplate rabbitTemplate, RabbitPublishFailureClassifier failureClassifier) {
        this.rabbitTemplate = Objects.requireNonNull(rabbitTemplate, "rabbitTemplate");
        this.failureClassifier = Objects.requireNonNull(failureClassifier, "failureClassifier");
    }

    public RabbitPublishResult publish(Object message, RabbitPublishSpec spec) {
        publishOnce(message, spec, 1);
        return new RabbitPublishResult(1);
    }

    public String failureReason(MessagePublishException ex) {
        return failureClassifier.classify(ex);
    }

    private void publishOnce(Object message, RabbitPublishSpec spec, int attempt) {
        CorrelationData correlationData = new CorrelationData(correlationId(spec, attempt));
        try {
            if (spec.priority() == null) {
                rabbitTemplate.convertAndSend(spec.exchange(), spec.routingKey(), message, correlationData);
            } else {
                rabbitTemplate.convertAndSend(
                    spec.exchange(),
                    spec.routingKey(),
                    message,
                    amqpMessage -> {
                        amqpMessage.getMessageProperties().setPriority(Math.max(0, Math.min(spec.priority(), 10)));
                        return amqpMessage;
                    },
                    correlationData
                );
            }
            CorrelationData.Confirm confirm = correlationData.getFuture()
                .get(spec.normalizedConfirmTimeoutMs(), TimeUnit.MILLISECONDS);
            ReturnedMessage returned = correlationData.getReturned();
            if (returned != null) {
                throw new MessagePublishException(
                    "RabbitMQ message was returned as unroutable: exchange=%s routingKey=%s replyCode=%d replyText=%s"
                        .formatted(returned.getExchange(), returned.getRoutingKey(), returned.getReplyCode(), returned.getReplyText())
                );
            }
            if (!confirm.ack()) {
                throw new MessagePublishException("RabbitMQ publisher confirm was nacked: " + confirm.reason());
            }
        } catch (AmqpException ex) {
            throw new MessagePublishException("RabbitMQ message publish attempt failed", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new MessagePublishException("RabbitMQ message publish was interrupted", ex);
        } catch (ExecutionException ex) {
            throw new MessagePublishException("RabbitMQ publisher confirm failed", ex);
        } catch (TimeoutException ex) {
            throw new MessagePublishException("RabbitMQ publisher confirm timed out", ex);
        }
    }

    private String correlationId(RabbitPublishSpec spec, int attempt) {
        return "%s-attempt-%d-%s".formatted(spec.correlationIdPrefix(), attempt, UUID.randomUUID());
    }

}
