package com.repoguard.agent.notification.delivery;

import com.rabbitmq.client.Channel;
import com.repoguard.agent.config.WorkerRuntimeEnabled;
import com.repoguard.agent.entity.NotificationEvent;
import com.repoguard.agent.notification.NotificationEventMessage;
import com.repoguard.agent.notification.NotificationMessage;
import com.repoguard.agent.observability.LogContext;
import com.repoguard.agent.observability.TracePropagation;
import com.repoguard.agent.tenancy.TenantContext;
import com.repoguard.agent.tenancy.TenantRuntimeGuard;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Component
@WorkerRuntimeEnabled
public class NotificationDeliveryWorker {

    private static final Logger LOGGER =
        LoggerFactory.getLogger("com.repoguard.agent.notification.NotificationDeliveryWorker");

    private final NotificationDeliveryClaimService deliveryClaimService;
    private final NotificationEventPayloadParser payloadParser;
    private final NotificationBindingBatchDeliveryService bindingBatchDeliveryService;
    private final NotificationDeliveryCompletionService deliveryCompletionService;
    private final NotificationDeliveryWorkerMetricsRecorder metricsRecorder;
    private final NotificationDeliveryFailureClassifier failureClassifier;
    private final NotificationDeliveryLogContextFormatter logContextFormatter;
    private JdbcTemplate jdbcTemplate;
    private TenantRuntimeGuard tenantRuntimeGuard;

    @Autowired
    public NotificationDeliveryWorker(
        NotificationDeliveryClaimService deliveryClaimService,
        NotificationEventPayloadParser payloadParser,
        NotificationBindingBatchDeliveryService bindingBatchDeliveryService,
        NotificationDeliveryCompletionService deliveryCompletionService,
        NotificationDeliveryWorkerMetricsRecorder metricsRecorder,
        NotificationDeliveryFailureClassifier failureClassifier,
        NotificationDeliveryLogContextFormatter logContextFormatter,
        JdbcTemplate jdbcTemplate,
        TenantRuntimeGuard tenantRuntimeGuard
    ) {
        this(
            deliveryClaimService,
            payloadParser,
            bindingBatchDeliveryService,
            deliveryCompletionService,
            metricsRecorder,
            failureClassifier,
            logContextFormatter
        );
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        this.tenantRuntimeGuard = Objects.requireNonNull(tenantRuntimeGuard, "tenantRuntimeGuard");
    }

    public NotificationDeliveryWorker(
        NotificationDeliveryClaimService deliveryClaimService,
        NotificationEventPayloadParser payloadParser,
        NotificationBindingBatchDeliveryService bindingBatchDeliveryService,
        NotificationDeliveryCompletionService deliveryCompletionService,
        NotificationDeliveryWorkerMetricsRecorder metricsRecorder,
        NotificationDeliveryFailureClassifier failureClassifier,
        NotificationDeliveryLogContextFormatter logContextFormatter,
        JdbcTemplate jdbcTemplate
    ) {
        this(
            deliveryClaimService,
            payloadParser,
            bindingBatchDeliveryService,
            deliveryCompletionService,
            metricsRecorder,
            failureClassifier,
            logContextFormatter
        );
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    }

    public NotificationDeliveryWorker(
        NotificationDeliveryClaimService deliveryClaimService,
        NotificationEventPayloadParser payloadParser,
        NotificationBindingBatchDeliveryService bindingBatchDeliveryService,
        NotificationDeliveryCompletionService deliveryCompletionService,
        NotificationDeliveryWorkerMetricsRecorder metricsRecorder,
        NotificationDeliveryFailureClassifier failureClassifier,
        NotificationDeliveryLogContextFormatter logContextFormatter
    ) {
        this.deliveryClaimService = deliveryClaimService;
        this.payloadParser = payloadParser;
        this.bindingBatchDeliveryService = bindingBatchDeliveryService;
        this.deliveryCompletionService = deliveryCompletionService;
        this.metricsRecorder = metricsRecorder;
        this.failureClassifier = Objects.requireNonNull(failureClassifier, "failureClassifier");
        this.logContextFormatter = Objects.requireNonNull(logContextFormatter, "logContextFormatter");
    }

    public void handle(
        NotificationEventMessage message,
        Channel channel,
        long deliveryTag
    ) throws IOException {
        handle(message, channel, deliveryTag, null);
    }

    @RabbitListener(queues = "${app.rabbit.notification.queue}", concurrency = "${app.rabbit.notification.worker-concurrency:1}")
    public void handle(
        NotificationEventMessage message,
        Channel channel,
        @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag,
        @Header(name = TracePropagation.TRACEPARENT_HEADER, required = false) String traceparent
    ) throws IOException {
        long startedAt = metricsRecorder.startedAt();
        try (TracePropagation.Scope _ = TracePropagation.openIncoming(traceparent)) {
            try (LogContext.TraceScope _ = LogContext.withTraceId(
                message.traceId() == null ? TracePropagation.currentTraceId() : message.traceId()
            )) {
            try {
                long tenantId = resolveTenantId(message);
                if (tenantRuntimeGuard != null) {
                    tenantRuntimeGuard.requireActive(tenantId);
                }
                try (TenantContext.Scope _ = TenantContext.withTenant(tenantId)) {
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
                }
            } catch (RuntimeException ex) {
                rejectRuntimeFailure(message, channel, deliveryTag, startedAt, ex);
                return;
            } catch (Error error) {
                rejectFatalFailure(message, channel, deliveryTag, startedAt, error);
                throw error;
            }
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
            }
        }
    }

    private long resolveTenantId(NotificationEventMessage message) {
        Long tenantId = message.tenantId();
        if (tenantId == null && jdbcTemplate != null) {
            tenantId = jdbcTemplate.queryForObject(
                "select tenant_id from notification_event where id = ?",
                Long.class,
                message.eventId()
            );
        }
        if (tenantId == null) {
            return TenantContext.DEFAULT_TENANT_ID;
        }
        if (tenantId < 1) {
            throw new IllegalStateException("Notification tenant id must be positive");
        }
        return tenantId;
    }

    private void rejectRuntimeFailure(
        NotificationEventMessage message,
        Channel channel,
        long deliveryTag,
        long startedAt,
        RuntimeException ex
    ) throws IOException {
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

    private void rejectFatalFailure(
        NotificationEventMessage message,
        Channel channel,
        long deliveryTag,
        long startedAt,
        Error error
    ) throws IOException {
        channel.basicReject(deliveryTag, false);
        try {
            metricsRecorder.recordConsumed(startedAt, "rejected", "notification_delivery_error");
            LOGGER.error(
                "Rabbit notification message rejected after fatal delivery error eventId={} eventKey={} eventType={} taskId={} batchId={} operation=rabbit_consume result=rejected requeue=false durationMs={} deliveryTag={} exceptionType={} failureCategory=notification_delivery_error",
                message.eventId(),
                logContextFormatter.safePart(message.eventKey()),
                logContextFormatter.safePart(message.eventType()),
                message.taskId(),
                message.batchId(),
                metricsRecorder.elapsedMillis(startedAt),
                deliveryTag,
                error.getClass().getName()
            );
        } catch (Throwable telemetryFailure) {
            error.addSuppressed(telemetryFailure);
        }
    }

    void deliver(Long eventId) {
        Optional<NotificationEvent> deliverableEvent = deliveryClaimService.claim(eventId);
        if (deliverableEvent.isEmpty()) {
            return;
        }
        NotificationEvent event = deliverableEvent.get();
        NotificationMessage message = payloadParser.parse(event);
        NotificationDeliveryResultSummary resultSummary = bindingBatchDeliveryService.deliver(event, message);
        deliveryCompletionService.complete(event, resultSummary);
    }
}
