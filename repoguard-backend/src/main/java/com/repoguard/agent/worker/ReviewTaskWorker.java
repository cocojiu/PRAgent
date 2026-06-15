package com.repoguard.agent.worker;

import com.rabbitmq.client.Channel;
import com.repoguard.agent.messaging.ReviewTaskMessage;
import com.repoguard.agent.observability.LogContext;
import com.repoguard.agent.observability.RepoGuardMetrics;
import java.io.IOException;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Component
public class ReviewTaskWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReviewTaskWorker.class);

    private final ReviewTaskExecutor reviewTaskExecutor;
    private final RepoGuardMetrics metrics;

    public ReviewTaskWorker(ReviewTaskExecutor reviewTaskExecutor, RepoGuardMetrics metrics) {
        this.reviewTaskExecutor = reviewTaskExecutor;
        this.metrics = metrics;
    }

    @RabbitListener(queues = "${app.rabbit.review.queue}", concurrency = "${app.rabbit.review.worker-concurrency:1}")
    public void handle(
        ReviewTaskMessage message,
        Channel channel,
        @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag
    ) throws IOException {
        long startedAt = System.nanoTime();
        try (LogContext.Scope ignored = LogContext.withReviewTaskMessage(message)) {
            LOGGER.info(
                "Rabbit review message received taskId={} repository={}/{} prNumber={} operation=rabbit_consume result=received deliveryTag={} commit={}",
                message.taskId(),
                safePart(message.organization()),
                safePart(message.repository()),
                message.prNumber(),
                deliveryTag,
                safePart(message.commit())
            );
            reviewTaskExecutor.execute(message);
            channel.basicAck(deliveryTag, false);
            recordConsumed(startedAt, "success");
            LOGGER.info(
                "Rabbit review message consumed taskId={} repository={}/{} prNumber={} operation=rabbit_consume result=success durationMs={} deliveryTag={}",
                message.taskId(),
                safePart(message.organization()),
                safePart(message.repository()),
                message.prNumber(),
                elapsedMillis(startedAt),
                deliveryTag
            );
        } catch (RuntimeException ex) {
            channel.basicReject(deliveryTag, false);
            recordConsumed(startedAt, "rejected");
            LOGGER.warn(
                "Rabbit review message rejected taskId={} repository={}/{} prNumber={} operation=rabbit_consume result=rejected requeue=false durationMs={} deliveryTag={} exceptionType={} failureCategory={}",
                message.taskId(),
                safePart(message.organization()),
                safePart(message.repository()),
                message.prNumber(),
                elapsedMillis(startedAt),
                deliveryTag,
                ex.getClass().getName(),
                ex.getClass().getSimpleName()
            );
        }
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
