package com.repoguard.agent.worker;

import com.repoguard.agent.messaging.ReviewTaskMessage;
import com.repoguard.agent.observability.RepoGuardMetrics;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
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
    public void handle(ReviewTaskMessage message) {
        long startedAt = System.nanoTime();
        try {
            reviewTaskExecutor.execute(message);
            recordConsumed(startedAt, "success");
            LOGGER.info(
                "Rabbit review message consumed taskId={} repository={}/{} prNumber={} operation=rabbit_consume result=success durationMs={}",
                message.taskId(),
                safePart(message.organization()),
                safePart(message.repository()),
                message.prNumber(),
                elapsedMillis(startedAt)
            );
        } catch (RuntimeException ex) {
            recordConsumed(startedAt, "failed");
            LOGGER.warn(
                "Rabbit review message failed taskId={} repository={}/{} prNumber={} operation=rabbit_consume result=failed durationMs={} exceptionType={}",
                message.taskId(),
                safePart(message.organization()),
                safePart(message.repository()),
                message.prNumber(),
                elapsedMillis(startedAt),
                ex.getClass().getName()
            );
            throw ex;
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
