package com.repoguard.agent.messaging;

import com.repoguard.agent.config.RabbitReviewQueueProperties;
import com.repoguard.agent.observability.RepoGuardMetrics;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class RabbitReviewTaskPublisher implements ReviewTaskPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final RabbitReviewQueueProperties properties;
    private final RepoGuardMetrics metrics;

    RabbitReviewTaskPublisher(RabbitTemplate rabbitTemplate, RabbitReviewQueueProperties properties) {
        this(rabbitTemplate, properties, null);
    }

    @Autowired
    public RabbitReviewTaskPublisher(
        RabbitTemplate rabbitTemplate,
        RabbitReviewQueueProperties properties,
        RepoGuardMetrics metrics
    ) {
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
        this.metrics = metrics;
    }

    @Override
    public void publish(ReviewTaskMessage message) {
        int attempts = Math.max(1, properties.getPublishMaxAttempts());
        long backoffMs = Math.max(0, properties.getPublishInitialIntervalMs());
        MessagePublishException lastFailure = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                publishOnce(message, attempt);
                return;
            } catch (MessagePublishException ex) {
                lastFailure = ex;
                if (attempt == attempts) {
                    break;
                }
                sleepBeforeRetry(backoffMs);
                backoffMs = nextBackoff(backoffMs);
            }
        }
        if (metrics != null) {
            metrics.rabbitPublishFailed(failureReason(lastFailure));
        }
        throw lastFailure == null
            ? new MessagePublishException("RabbitMQ message publish failed")
            : lastFailure;
    }

    private void publishOnce(ReviewTaskMessage message, int attempt) {
        CorrelationData correlationData = new CorrelationData(correlationId(message, attempt));
        try {
            rabbitTemplate.convertAndSend(properties.getExchange(), properties.getRoutingKey(), message, correlationData);
            CorrelationData.Confirm confirm = correlationData.getFuture()
                .get(Math.max(1, properties.getPublishConfirmTimeoutMs()), TimeUnit.MILLISECONDS);
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

    private String correlationId(ReviewTaskMessage message, int attempt) {
        return "review-task-%d-attempt-%d-%s".formatted(message.taskId(), attempt, UUID.randomUUID());
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

    private long nextBackoff(long currentBackoffMs) {
        double multiplier = properties.getPublishMultiplier() <= 0 ? 1.0 : properties.getPublishMultiplier();
        return Math.max(0, Math.round(currentBackoffMs * multiplier));
    }

    private String failureReason(MessagePublishException ex) {
        if (ex == null || ex.getMessage() == null || ex.getMessage().isBlank()) {
            return "publish_failed";
        }
        String message = ex.getMessage().toLowerCase();
        if (message.contains("unroutable")) {
            return "unroutable";
        }
        if (message.contains("nacked")) {
            return "nacked";
        }
        if (message.contains("timed out")) {
            return "confirm_timeout";
        }
        if (message.contains("interrupted")) {
            return "interrupted";
        }
        return "publish_failed";
    }
}
