package com.repoguard.agent.worker;

import com.rabbitmq.client.Channel;
import com.repoguard.agent.config.WorkerRuntimeEnabled;
import com.repoguard.agent.review.task.ReviewTaskMessage;
import com.repoguard.agent.observability.LogContext;
import com.repoguard.agent.observability.TracePropagation;
import java.io.IOException;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Component
@WorkerRuntimeEnabled
public class ReviewTaskWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReviewTaskWorker.class);

    private final ReviewTaskExecutor reviewTaskExecutor;
    private final ReviewTaskWorkerMetricsRecorder metricsRecorder;
    private final ReviewLogContextFormatter logContextFormatter;
    private final ReviewExecutionFailureClassifier failureClassifier;

    public ReviewTaskWorker(
        ReviewTaskExecutor reviewTaskExecutor,
        ReviewTaskWorkerMetricsRecorder metricsRecorder,
        ReviewLogContextFormatter logContextFormatter,
        ReviewExecutionFailureClassifier failureClassifier
    ) {
        this.reviewTaskExecutor = reviewTaskExecutor;
        this.metricsRecorder = metricsRecorder;
        this.logContextFormatter = Objects.requireNonNull(logContextFormatter, "logContextFormatter");
        this.failureClassifier = Objects.requireNonNull(failureClassifier, "failureClassifier");
    }

    public void handle(
        ReviewTaskMessage message,
        Channel channel,
        long deliveryTag
    ) throws IOException {
        handle(message, channel, deliveryTag, null);
    }

    @RabbitListener(queues = "${app.rabbit.review.queue}", concurrency = "${app.rabbit.review.worker-concurrency:1}")
    public void handle(
        ReviewTaskMessage message,
        Channel channel,
        @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag,
        @Header(name = TracePropagation.TRACEPARENT_HEADER, required = false) String traceparent
    ) throws IOException {
        long startedAt = metricsRecorder.startedAt();
        try (TracePropagation.Scope _ = TracePropagation.openIncoming(traceparent)) {
            try (LogContext.Scope _ = LogContext.withReviewTaskMessage(message)) {
            LOGGER.info(
                "Rabbit review message received taskId={} repository={} prNumber={} operation=rabbit_consume result=received deliveryTag={} commit={}",
                message.taskId(),
                logContextFormatter.repositorySlug(message),
                message.prNumber(),
                deliveryTag,
                logContextFormatter.safePart(message.commit())
            );
            try {
                reviewTaskExecutor.execute(message);
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
                "Rabbit review message consumed taskId={} repository={} prNumber={} operation=rabbit_consume result=success durationMs={} deliveryTag={}",
                message.taskId(),
                logContextFormatter.repositorySlug(message),
                message.prNumber(),
                metricsRecorder.elapsedMillis(startedAt),
                deliveryTag
            );
            }
        }
    }

    private void rejectRuntimeFailure(
        ReviewTaskMessage message,
        Channel channel,
        long deliveryTag,
        long startedAt,
        RuntimeException ex
    ) throws IOException {
        channel.basicReject(deliveryTag, false);
        String failureCategory = failureClassifier.failureCategory(ex);
        metricsRecorder.recordConsumed(startedAt, "rejected", failureCategory);
        LOGGER.warn(
            "Rabbit review message rejected taskId={} repository={} prNumber={} operation=rabbit_consume result=rejected requeue=false durationMs={} deliveryTag={} exceptionType={} failureCategory={}",
            message.taskId(),
            logContextFormatter.repositorySlug(message),
            message.prNumber(),
            metricsRecorder.elapsedMillis(startedAt),
            deliveryTag,
            ex.getClass().getName(),
            failureCategory
        );
    }

    private void rejectFatalFailure(
        ReviewTaskMessage message,
        Channel channel,
        long deliveryTag,
        long startedAt,
        Error error
    ) throws IOException {
        channel.basicReject(deliveryTag, false);
        try {
            metricsRecorder.recordConsumed(startedAt, "rejected", "review_execution_error");
            LOGGER.error(
                "Rabbit review message rejected after fatal execution error taskId={} repository={} prNumber={} operation=rabbit_consume result=rejected requeue=false durationMs={} deliveryTag={} exceptionType={} failureCategory=review_execution_error",
                message.taskId(),
                logContextFormatter.repositorySlug(message),
                message.prNumber(),
                metricsRecorder.elapsedMillis(startedAt),
                deliveryTag,
                error.getClass().getName()
            );
        } catch (Throwable telemetryFailure) {
            error.addSuppressed(telemetryFailure);
        }
    }
}
