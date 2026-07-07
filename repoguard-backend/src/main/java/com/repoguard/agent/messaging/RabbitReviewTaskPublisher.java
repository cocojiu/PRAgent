package com.repoguard.agent.messaging;

import com.repoguard.agent.config.RabbitReviewQueueProperties;
import com.repoguard.agent.observability.LogContext;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class RabbitReviewTaskPublisher implements ReviewTaskPublisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(RabbitReviewTaskPublisher.class);

    private final RabbitReliableMessagePublisher reliablePublisher;
    private final RabbitReviewQueueProperties properties;
    private final RabbitPublishSpecFactory specFactory;
    private final RabbitPublishFailureMetricsRecorder metricsRecorder;

    @Autowired
    public RabbitReviewTaskPublisher(
        RabbitReliableMessagePublisher reliablePublisher,
        RabbitReviewQueueProperties properties,
        RabbitPublishSpecFactory specFactory,
        RabbitPublishFailureMetricsRecorder metricsRecorder
    ) {
        this.reliablePublisher = Objects.requireNonNull(reliablePublisher, "reliablePublisher");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.specFactory = Objects.requireNonNull(specFactory, "specFactory");
        this.metricsRecorder = Objects.requireNonNull(metricsRecorder, "metricsRecorder");
    }

    @Override
    public void publish(ReviewTaskMessage message) {
        try (LogContext.Scope ignored = LogContext.withReviewTaskMessage(message)) {
            try {
                RabbitPublishResult result = reliablePublisher.publish(message, spec(message));
                LOGGER.info(
                    "Rabbit review message published taskId={} repository={}/{} prNumber={} operation=rabbit_publish result=success attempt={} exchange={} routingKey={}",
                    message.taskId(),
                    safePart(message.organization()),
                    safePart(message.repository()),
                    message.prNumber(),
                    result.attempt(),
                    properties.getExchange(),
                    properties.getRoutingKey()
                );
            } catch (MessagePublishException ex) {
                String failureReason = reliablePublisher.failureReason(ex);
                LOGGER.warn(
                    "Rabbit review message publish failed taskId={} repository={}/{} prNumber={} operation=rabbit_publish result=failed maxAttempts={} failureReason={}",
                    message.taskId(),
                    safePart(message.organization()),
                    safePart(message.repository()),
                    message.prNumber(),
                    Math.max(1, properties.getPublishMaxAttempts()),
                    failureReason
                );
                metricsRecorder.recordFailed(RabbitPublishFailurePhase.PUBLISH, failureReason);
                throw ex;
            }
        }
    }

    private RabbitPublishSpec spec(ReviewTaskMessage message) {
        return specFactory.reviewTask(properties, message.taskId());
    }

    private String safePart(String value) {
        return value == null || value.isBlank() ? "<unknown>" : value.trim();
    }
}
