package com.repoguard.agent.messaging;

import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class RabbitReliableMessagePublisher {

    private final RabbitTemplate rabbitTemplate;
    private final RabbitPublishFailureClassifier failureClassifier;

    public RabbitReliableMessagePublisher(RabbitTemplate rabbitTemplate) {
        this(rabbitTemplate, new RabbitPublishFailureClassifier());
    }

    RabbitReliableMessagePublisher(RabbitTemplate rabbitTemplate, RabbitPublishFailureClassifier failureClassifier) {
        this.rabbitTemplate = rabbitTemplate;
        this.failureClassifier = failureClassifier;
    }

    public RabbitPublishResult publish(Object message, RabbitPublishSpec spec) {
        int attempts = spec.normalizedMaxAttempts();
        long backoffMs = spec.normalizedInitialBackoffMs();
        MessagePublishException lastFailure = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                publishOnce(message, spec, attempt);
                return new RabbitPublishResult(attempt);
            } catch (MessagePublishException ex) {
                lastFailure = ex;
                if (attempt == attempts) {
                    break;
                }
                sleepBeforeRetry(backoffMs);
                backoffMs = nextBackoff(backoffMs, spec.normalizedBackoffMultiplier());
            }
        }
        throw lastFailure == null ? new MessagePublishException("RabbitMQ message publish failed") : lastFailure;
    }

    public String failureReason(MessagePublishException ex) {
        return failureClassifier.classify(ex);
    }

    private void publishOnce(Object message, RabbitPublishSpec spec, int attempt) {
        CorrelationData correlationData = new CorrelationData(correlationId(spec, attempt));
        try {
            rabbitTemplate.convertAndSend(spec.exchange(), spec.routingKey(), message, correlationData);
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

    private void sleepBeforeRetry(long backoffMs) {
        if (backoffMs <= 0) {
            return;
        }
        try {
            Thread.sleep(backoffMs);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new MessagePublishException("RabbitMQ publish retry sleep was interrupted", ex);
        }
    }

    private long nextBackoff(long currentBackoffMs, double multiplier) {
        return Math.max(0, Math.round(currentBackoffMs * multiplier));
    }
}
