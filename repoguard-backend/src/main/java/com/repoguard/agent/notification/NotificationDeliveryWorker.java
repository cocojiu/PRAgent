package com.repoguard.agent.notification;

import com.rabbitmq.client.Channel;
import com.repoguard.agent.config.WorkerRuntimeEnabled;
import com.repoguard.agent.entity.NotificationEvent;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Component
@WorkerRuntimeEnabled
public class NotificationDeliveryWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(NotificationDeliveryWorker.class);

    private final NotificationDeliverableEventQuery deliverableEventQuery;
    private final NotificationEventPayloadParser payloadParser;
    private final NotificationBindingBatchDeliveryService bindingBatchDeliveryService;
    private final NotificationDeliveryCompletionService deliveryCompletionService;
    private final NotificationDeliveryEventStateUpdater eventStateUpdater;
    private final NotificationDeliveryWorkerMetricsRecorder metricsRecorder;
    private final NotificationDeliveryFailureClassifier failureClassifier;
    private final NotificationDeliveryLogContextFormatter logContextFormatter;

    public NotificationDeliveryWorker(
        NotificationDeliverableEventQuery deliverableEventQuery,
        NotificationEventPayloadParser payloadParser,
        NotificationBindingBatchDeliveryService bindingBatchDeliveryService,
        NotificationDeliveryCompletionService deliveryCompletionService,
        NotificationDeliveryEventStateUpdater eventStateUpdater,
        NotificationDeliveryWorkerMetricsRecorder metricsRecorder,
        NotificationDeliveryFailureClassifier failureClassifier,
        NotificationDeliveryLogContextFormatter logContextFormatter
    ) {
        this.deliverableEventQuery = deliverableEventQuery;
        this.payloadParser = payloadParser;
        this.bindingBatchDeliveryService = bindingBatchDeliveryService;
        this.deliveryCompletionService = deliveryCompletionService;
        this.eventStateUpdater = eventStateUpdater;
        this.metricsRecorder = metricsRecorder;
        this.failureClassifier = Objects.requireNonNull(failureClassifier, "failureClassifier");
        this.logContextFormatter = Objects.requireNonNull(logContextFormatter, "logContextFormatter");
    }

    @RabbitListener(queues = "${app.rabbit.notification.queue}", concurrency = "${app.rabbit.notification.worker-concurrency:1}")
    public void handle(
        NotificationEventMessage message,
        Channel channel,
        @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag
    ) throws IOException {
        long startedAt = metricsRecorder.startedAt();
        try {
            LOGGER.info(
                "Rabbit notification message received eventId={} eventKey={} eventType={} taskId={} batchId={} operation=rabbit_consume result=received deliveryTag={}",
                message.eventId(),
                logContextFormatter.safePart(message.eventKey()),
                logContextFormatter.safePart(message.eventType()),
                message.taskId(),
                message.batchId(),
                deliveryTag
            );
            deliver(message.eventId());
            channel.basicAck(deliveryTag, false);
            metricsRecorder.recordConsumed(startedAt, "success");
            LOGGER.info(
                "Rabbit notification message consumed eventId={} eventKey={} eventType={} taskId={} batchId={} operation=rabbit_consume result=success durationMs={} deliveryTag={}",
                message.eventId(),
                logContextFormatter.safePart(message.eventKey()),
                logContextFormatter.safePart(message.eventType()),
                message.taskId(),
                message.batchId(),
                metricsRecorder.elapsedMillis(startedAt),
                deliveryTag
            );
        } catch (RuntimeException ex) {
            channel.basicReject(deliveryTag, false);
            String failureCategory = failureClassifier.failureCategory(ex);
            metricsRecorder.recordConsumed(startedAt, "rejected", failureCategory);
            LOGGER.warn(
                "Rabbit notification message rejected eventId={} eventKey={} eventType={} taskId={} batchId={} operation=rabbit_consume result=rejected requeue=false durationMs={} deliveryTag={} exceptionType={} failureCategory={}",
                message.eventId(),
                logContextFormatter.safePart(message.eventKey()),
                logContextFormatter.safePart(message.eventType()),
                message.taskId(),
                message.batchId(),
                metricsRecorder.elapsedMillis(startedAt),
                deliveryTag,
                ex.getClass().getName(),
                failureCategory
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
}
