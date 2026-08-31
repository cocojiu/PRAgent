package com.repoguard.agent.observability;

import com.repoguard.agent.external.ExternalCallException;
import java.time.Duration;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class ExternalMetricsRecorder {

    private final MetricRecorderSupport metrics;

    public ExternalMetricsRecorder(MetricRecorderSupport metrics) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    void callFailed(ExternalCallException ex) {
        metrics.counter(
            "repoguard.external.call.failed",
            "system", metrics.normalize(ex.getSystem()),
            "category", metrics.normalize(ex.getCategory()),
            "retryable", Boolean.toString(ex.isRetryable()),
            "status", ex.getStatusCode() == null ? "none" : ex.getStatusCode().toString()
        ).increment();
    }

    void callRetried(ExternalCallException ex, int attempt) {
        metrics.counter(
            "repoguard.external.call.retry",
            "system", metrics.normalize(ex.getSystem()),
            "category", metrics.normalize(ex.getCategory()),
            "retryable", Boolean.toString(ex.isRetryable()),
            "status", ex.getStatusCode() == null ? "none" : ex.getStatusCode().toString(),
            "attempt", Integer.toString(Math.max(1, attempt))
        ).increment();
    }

    void githubApiRequest(Duration duration, String operation, String result, String category, String status) {
        String[] tags = {
            "operation", metrics.normalize(operation),
            "result", metrics.normalize(result),
            "category", metrics.normalize(category),
            "status", metrics.normalize(status)
        };
        metrics.timer("repoguard.github.api.request.duration", tags).record(metrics.nonNegative(duration));
        metrics.counter("repoguard.github.api.request", tags).increment();
    }

    void githubDiffDuration(Duration duration, String result) {
        metrics.timer("repoguard.github.diff.duration", "result", metrics.normalize(result))
            .record(metrics.nonNegative(duration));
    }

    void llmRequestDuration(Duration duration, String result) {
        metrics.timer("repoguard.llm.request.duration", "result", metrics.normalize(result))
            .record(metrics.nonNegative(duration));
    }

    void llmFallback(String reason) {
        metrics.counter("repoguard.llm.fallback", "reason", metrics.normalize(reason)).increment();
    }

    void llmStructuredOutput(String provider, String mode, String outcome, String reason) {
        String[] tags = {
            "provider", metrics.normalize(provider),
            "mode", metrics.normalize(mode),
            "outcome", metrics.normalize(outcome),
            "reason", metrics.normalize(reason)
        };
        metrics.counter("repoguard.llm.structured_output", tags).increment();
    }

    void githubCommentPublished(String status) {
        metrics.counter("repoguard.github.comment.publish", "status", metrics.normalize(status)).increment();
    }

    void githubCommentPublishDuration(Duration duration, String result) {
        metrics.timer("repoguard.github.comment.publish.duration", "result", metrics.normalize(result))
            .record(metrics.nonNegative(duration));
    }
}
