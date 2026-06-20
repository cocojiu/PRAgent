package com.repoguard.agent.notification;

import com.rabbitmq.client.Channel;
import com.repoguard.agent.entity.NotificationEvent;
import java.io.IOException;
import java.util.Optional;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Component
public class NotificationDeliveryWorker {

    private final NotificationDeliverableEventQuery deliverableEventQuery;
    private final NotificationEventPayloadParser payloadParser;
    private final NotificationBindingBatchDeliveryService bindingBatchDeliveryService;
    private final NotificationDeliveryCompletionService deliveryCompletionService;
    private final NotificationDeliveryEventStateUpdater eventStateUpdater;

    public NotificationDeliveryWorker(
        NotificationDeliverableEventQuery deliverableEventQuery,
        NotificationEventPayloadParser payloadParser,
        NotificationBindingBatchDeliveryService bindingBatchDeliveryService,
        NotificationDeliveryCompletionService deliveryCompletionService,
        NotificationDeliveryEventStateUpdater eventStateUpdater
    ) {
        this.deliverableEventQuery = deliverableEventQuery;
        this.payloadParser = payloadParser;
        this.bindingBatchDeliveryService = bindingBatchDeliveryService;
        this.deliveryCompletionService = deliveryCompletionService;
        this.eventStateUpdater = eventStateUpdater;
    }

    @RabbitListener(queues = "${app.rabbit.notification.queue}", concurrency = "${app.rabbit.notification.worker-concurrency:1}")
    public void handle(
        NotificationEventMessage message,
        Channel channel,
        @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag
    ) throws IOException {
        try {
            deliver(message.eventId());
            channel.basicAck(deliveryTag, false);
        } catch (RuntimeException ex) {
            channel.basicReject(deliveryTag, false);
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
