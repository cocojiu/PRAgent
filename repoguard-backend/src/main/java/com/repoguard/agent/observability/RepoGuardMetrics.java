package com.repoguard.agent.observability;

import com.repoguard.agent.external.ExternalCallException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class RepoGuardMetrics {

    private static final String UNKNOWN = "unknown";

    private final MeterRegistry meterRegistry;

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

    public void rabbitPublishFailed(String reason) {
        counter("repoguard.rabbit.publish.failed", "reason", normalize(reason)).increment();
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
