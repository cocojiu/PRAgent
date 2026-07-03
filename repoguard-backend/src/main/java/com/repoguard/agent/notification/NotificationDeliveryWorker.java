package com.repoguard.agent.notification;

import com.rabbitmq.client.Channel;
import com.repoguard.agent.entity.NotificationEvent;
import com.repoguard.agent.observability.RepoGuardMetrics;
import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Component
public class NotificationDeliveryWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(NotificationDeliveryWorker.class);

    private final NotificationDeliverableEventQuery deliverableEventQuery;
    private final NotificationEventPayloadParser payloadParser;
    private final NotificationBindingBatchDeliveryService bindingBatchDeliveryService;
    private final NotificationDeliveryCompletionService deliveryCompletionService;
    private final NotificationDeliveryEventStateUpdater eventStateUpdater;
    private final RepoGuardMetrics metrics;

    public NotificationDeliveryWorker(
        NotificationDeliverableEventQuery deliverableEventQuery,
        NotificationEventPayloadParser payloadParser,
        NotificationBindingBatchDeliveryService bindingBatchDeliveryService,
        NotificationDeliveryCompletionService deliveryCompletionService,
        NotificationDeliveryEventStateUpdater eventStateUpdater,
        RepoGuardMetrics metrics
    ) {
        this.deliverableEventQuery = deliverableEventQuery;
        this.payloadParser = payloadParser;
        this.bindingBatchDeliveryService = bindingBatchDeliveryService;
        this.deliveryCompletionService = deliveryCompletionService;
        this.eventStateUpdater = eventStateUpdater;
        this.metrics = metrics;
    }

    @RabbitListener(queues = "${app.rabbit.notification.queue}", concurrency = "${app.rabbit.notification.worker-concurrency:1}")
    public void handle(
        NotificationEventMessage message,
        Channel channel,
        @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag
    ) throws IOException {
        long startedAt = System.nanoTime();
        try {
            LOGGER.info(
                "Rabbit notification message received eventId={} eventKey={} eventType={} taskId={} batchId={} operation=rabbit_consume result=received deliveryTag={}",
                message.eventId(),
                safePart(message.eventKey()),
                safePart(message.eventType()),
                message.taskId(),
                message.batchId(),
                deliveryTag
            );
            deliver(message.eventId());
            channel.basicAck(deliveryTag, false);
            recordConsumed(startedAt, "success");
            LOGGER.info(
                "Rabbit notification message consumed eventId={} eventKey={} eventType={} taskId={} batchId={} operation=rabbit_consume result=success durationMs={} deliveryTag={}",
                message.eventId(),
                safePart(message.eventKey()),
                safePart(message.eventType()),
                message.taskId(),
                message.batchId(),
                elapsedMillis(startedAt),
                deliveryTag
            );
        } catch (RuntimeException ex) {
            channel.basicReject(deliveryTag, false);
            recordConsumed(startedAt, "rejected");
            LOGGER.warn(
                "Rabbit notification message rejected eventId={} eventKey={} eventType={} taskId={} batchId={} operation=rabbit_consume result=rejected requeue=false durationMs={} deliveryTag={} exceptionType={} failureCategory={}",
                message.eventId(),
                safePart(message.eventKey()),
                safePart(message.eventType()),
                message.taskId(),
                message.batchId(),
                elapsedMillis(startedAt),
                deliveryTag,
                ex.getClass().getName(),
                ex.getClass().getSimpleName()
            );
        }
    }

    void deliver(Long eventId) {
        Optional<NotificationEvent> deliverableEvent = deliverableEventQuery.load(eventId);
        if (deliverableEvent.isEmpty()) {
            return;
        }
        NotificationEvent event = deliverableEvent.get();
        NotificationMessage message = payloadParser.parse(event);
        eventStateUpdater.markDelivering(event);

        NotificationDeliveryResultSummary resultSummary = bindingBatchDeliveryService.deliver(event, message);
        deliveryCompletionService.complete(event, resultSummary);
    }

    private void recordConsumed(long startedAt, String result) {
        if (metrics != null) {
            metrics.rabbitMessageConsumed(Duration.ofNanos(System.nanoTime() - startedAt), result);
        }
    }

    private long elapsedMillis(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
    }

    private String safePart(String value) {
        return value == null || value.isBlank() ? "<unknown>" : value.trim();
    }
}
