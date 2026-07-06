package com.repoguard.agent.service.impl;

import com.repoguard.agent.config.RabbitReviewQueueProperties;
import com.repoguard.agent.dto.MessageQueueMetricDto;
import com.repoguard.agent.mapper.ReviewTaskMapper.MessageQueueHealthSummary;
import com.repoguard.agent.observability.RepoGuardMetrics;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
class MessageQueueMetricAssembler {

    private final RabbitReviewQueueProperties properties;
    private final RepoGuardMetrics metrics;

    MessageQueueMetricAssembler(RabbitReviewQueueProperties properties, RepoGuardMetrics metrics) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.metrics = metrics;
    }

    List<MessageQueueMetricDto> assemble(MessageQueueHealthSummary summary) {
        long total = safeCount(summary == null ? null : summary.getTotal());
        long publishFailed = safeCount(summary == null ? null : summary.getPublishFailed());
        long executionTimeout = safeCount(summary == null ? null : summary.getExecutionTimeout());
        long requeuePending = safeCount(summary == null ? null : summary.getRequeuePending());
        long claimed = safeCount(summary == null ? null : summary.getClaimed());
        long dlqBacklog = safeCount(summary == null ? null : summary.getDlqBacklog());
        long publishSucceeded = Math.max(0, total - publishFailed - executionTimeout - requeuePending - dlqBacklog);
        recordQueueDepth(publishFailed, executionTimeout, requeuePending, claimed, dlqBacklog);

        return List.of(
            new MessageQueueMetricDto("Publish succeeded", String.valueOf(publishSucceeded), "Current active config", "trend", "blue"),
            new MessageQueueMetricDto("Publish failed", String.valueOf(publishFailed), "Waiting for compensation", trendClass(publishFailed), "red"),
            new MessageQueueMetricDto("Execution timeout", String.valueOf(executionTimeout), "Review lease expired", trendClass(executionTimeout), "orange"),
            new MessageQueueMetricDto("Requeue pending", String.valueOf(requeuePending), "Execution recovery publishing", trendClass(requeuePending), "orange"),
            new MessageQueueMetricDto("Compensating", String.valueOf(claimed), "Claimed by workers", trendClass(claimed), "orange"),
            new MessageQueueMetricDto("DLQ backlog", String.valueOf(dlqBacklog), "Database observed status", trendClass(dlqBacklog), "red")
        );
    }

    private String trendClass(long value) {
        return value > 0 ? "trend danger" : "trend";
    }

    private void recordQueueDepth(long publishFailed, long executionTimeout, long requeuePending, long claimed, long dlqBacklog) {
        if (metrics == null) {
            return;
        }
        metrics.rabbitQueueDepth(properties.getQueue(), "publish_failed", publishFailed);
        metrics.rabbitQueueDepth(properties.getQueue(), "execution_timeout", executionTimeout);
        metrics.rabbitQueueDepth(properties.getQueue(), "requeue_pending", requeuePending);
        metrics.rabbitQueueDepth(properties.getQueue(), "claimed", claimed);
        metrics.rabbitQueueDepth(properties.getDeadLetterQueue(), "dlq", dlqBacklog);
    }

    private long safeCount(Long value) {
        return value == null ? 0L : value;
    }
}
