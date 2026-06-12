package com.repoguard.agent.observability;

import com.repoguard.agent.external.ExternalCallException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class RepoGuardMetrics {

    private static final String UNKNOWN = "unknown";

    private final MeterRegistry meterRegistry;
    private final ConcurrentMap<String, AtomicLong> gauges = new ConcurrentHashMap<>();

    public RepoGuardMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void reviewTaskCreated(String source) {
        counter("repoguard.review.task.created", "source", normalize(source)).increment();
    }

    public void reviewTaskCompleted(String riskLevel, String llmStatus) {
        counter(
            "repoguard.review.task.completed",
            "risk_level", normalize(riskLevel),
            "llm_status", normalize(llmStatus)
        ).increment();
    }

    public void reviewTaskDuration(Duration duration, String result) {
        timer("repoguard.review.task.duration", "result", normalize(result))
            .record(nonNegative(duration));
    }

    public void reviewTaskFailed(RuntimeException ex) {
        counter(
            "repoguard.review.task.failed",
            "category", failureCategory(ex),
            "retryable", retryable(ex)
        ).increment();
    }

    public void externalCallFailed(ExternalCallException ex) {
        counter(
            "repoguard.external.call.failed",
            "system", normalize(ex.getSystem()),
            "category", normalize(ex.getCategory()),
            "retryable", Boolean.toString(ex.isRetryable()),
            "status", ex.getStatusCode() == null ? "none" : ex.getStatusCode().toString()
        ).increment();
    }

    public void githubApiRequest(Duration duration, String operation, String result, String category, String status) {
        timer(
            "repoguard.github.api.request.duration",
            "operation", normalize(operation),
            "result", normalize(result),
            "category", normalize(category),
            "status", normalize(status)
        ).record(nonNegative(duration));
        counter(
            "repoguard.github.api.request",
            "operation", normalize(operation),
            "result", normalize(result),
            "category", normalize(category),
            "status", normalize(status)
        ).increment();
    }

    public void githubDiffDuration(Duration duration, String result) {
        timer("repoguard.github.diff.duration", "result", normalize(result))
            .record(nonNegative(duration));
    }

    public void llmRequestDuration(Duration duration, String result) {
        timer("repoguard.llm.request.duration", "result", normalize(result))
            .record(nonNegative(duration));
    }

    public void llmFallback(String reason) {
        counter("repoguard.llm.fallback", "reason", normalize(reason)).increment();
    }

    public void githubCommentPublished(String status) {
        counter("repoguard.github.comment.publish", "status", normalize(status)).increment();
    }

    public void githubCommentPublishDuration(Duration duration, String result) {
        timer("repoguard.github.comment.publish.duration", "result", normalize(result))
            .record(nonNegative(duration));
    }

    public void rabbitPublishFailed(String reason) {
        counter("repoguard.rabbit.publish.failed", "reason", normalize(reason)).increment();
    }

    public void rabbitMessageConsumed(Duration duration, String result) {
        timer("repoguard.rabbit.consume.duration", "result", normalize(result))
            .record(nonNegative(duration));
        counter("repoguard.rabbit.consume", "result", normalize(result)).increment();
    }

    public void rabbitQueueDepth(String queue, String state, long depth) {
        String normalizedQueue = normalize(queue);
        String normalizedState = normalize(state);
        String key = normalizedQueue + "|" + normalizedState;
        AtomicLong value = gauges.computeIfAbsent(key, ignored -> {
            AtomicLong gaugeValue = new AtomicLong();
            io.micrometer.core.instrument.Gauge.builder("repoguard.rabbit.queue.depth", gaugeValue, AtomicLong::get)
                .tag("queue", normalizedQueue)
                .tag("state", normalizedState)
                .register(meterRegistry);
            return gaugeValue;
        });
        value.set(Math.max(0, depth));
    }

    public void rabbitPublishCompensationSucceeded() {
        counter("repoguard.rabbit.publish.compensation", "result", "success").increment();
    }

    public void rabbitPublishCompensationFailed(String reason) {
        counter(
            "repoguard.rabbit.publish.compensation",
            "result", "failed",
            "reason", normalize(reason)
        ).increment();
    }

    private Counter counter(String name, String... tags) {
        return Counter.builder(name)
            .tags(tags)
            .register(meterRegistry);
    }

    private Timer timer(String name, String... tags) {
        return Timer.builder(name)
            .tags(tags)
            .register(meterRegistry);
    }

    private Duration nonNegative(Duration duration) {
        if (duration == null || duration.isNegative()) {
            return Duration.ZERO;
        }
        return duration;
    }

    private String failureCategory(RuntimeException ex) {
        if (ex instanceof ExternalCallException externalCallException) {
            return normalize(externalCallException.getCategory());
        }
        return normalize(ex.getClass().getSimpleName());
    }

    private String retryable(RuntimeException ex) {
        if (ex instanceof ExternalCallException externalCallException) {
            return Boolean.toString(externalCallException.isRetryable());
        }
        return UNKNOWN;
    }

    private String normalize(String value) {
        if (!StringUtils.hasText(value)) {
            return UNKNOWN;
        }
        return value.trim().toLowerCase().replaceAll("[^a-z0-9_.-]+", "_");
    }
}
