package com.repoguard.agent.service.impl;

import com.repoguard.agent.config.RabbitMqIntegrationProvider;
import com.repoguard.agent.config.RabbitMqIntegrationSettings;
import com.repoguard.agent.config.RabbitReviewQueueProperties;
import com.repoguard.agent.dto.ActiveRabbitMqConfigDto;
import com.repoguard.agent.dto.MessageQueueExceptionTaskDto;
import com.repoguard.agent.dto.MessageQueueHealthResponse;
import com.repoguard.agent.dto.MessageQueueMetricDto;
import com.repoguard.agent.dto.RabbitMqTopologyDto;
import com.repoguard.agent.dto.RetryCompensationStatusDto;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.mapper.ReviewTaskMapper.MessageQueueHealthSummary;
import com.repoguard.agent.observability.RepoGuardMetrics;
import com.repoguard.agent.review.ReviewTaskStateMachine;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
class MessageQueueHealthQueryService {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter VERSION_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final ReviewTaskMapper reviewTaskMapper;
    private final RabbitMqIntegrationProvider rabbitMqIntegrationProvider;
    private final RabbitReviewQueueProperties properties;
    private final RabbitRuntimeHealthProbe runtimeHealthProbe;
    private final RepoGuardMetrics metrics;
    private final ReviewTaskStateMachine reviewTaskStateMachine;
    private final MessageQueueExceptionTaskAssembler exceptionTaskAssembler;

    @Autowired
    MessageQueueHealthQueryService(
        ReviewTaskMapper reviewTaskMapper,
        RabbitMqIntegrationProvider rabbitMqIntegrationProvider,
        RabbitReviewQueueProperties properties,
        RabbitTemplate rabbitTemplate,
        RepoGuardMetrics metrics,
        ReviewTaskStateMachine reviewTaskStateMachine
    ) {
        this(
            reviewTaskMapper,
            rabbitMqIntegrationProvider,
            properties,
            new RabbitRuntimeHealthProbe(rabbitTemplate, properties),
            metrics,
            reviewTaskStateMachine
        );
    }

    MessageQueueHealthQueryService(
        ReviewTaskMapper reviewTaskMapper,
        RabbitMqIntegrationProvider rabbitMqIntegrationProvider,
        RabbitReviewQueueProperties properties,
        RabbitRuntimeHealthProbe runtimeHealthProbe,
        RepoGuardMetrics metrics,
        ReviewTaskStateMachine reviewTaskStateMachine
    ) {
        this.reviewTaskMapper = reviewTaskMapper;
        this.rabbitMqIntegrationProvider = rabbitMqIntegrationProvider;
        this.properties = properties;
        this.runtimeHealthProbe = runtimeHealthProbe;
        this.metrics = metrics;
        this.reviewTaskStateMachine = reviewTaskStateMachine == null
            ? new ReviewTaskStateMachine()
            : reviewTaskStateMachine;
        this.exceptionTaskAssembler = new MessageQueueExceptionTaskAssembler(this.reviewTaskStateMachine);
    }

    MessageQueueHealthResponse getHealth() {
        MessageQueueHealthSummary summary = reviewTaskMapper.selectMessageQueueHealthSummary();
        List<ReviewTask> exceptionTasks = reviewTaskMapper.selectMessageQueueExceptionTasks();
        String latestFailureReason = reviewTaskMapper.selectLatestPublishFailureReason();
        RabbitMqIntegrationSettings settings = rabbitMqIntegrationProvider.getSettings();
        if (settings == null) {
            settings = RabbitMqIntegrationSettings.empty();
        }

        return new MessageQueueHealthResponse(
            activeConfig(settings),
            topology(),
            metrics(summary),
            retryCompensation(summary, latestFailureReason),
            exceptionTaskAssembler.assemble(exceptionTasks, maxAttempts()),
            format(LocalDateTime.now()),
            "DATABASE_TASK_STATE"
        );
    }

    private ActiveRabbitMqConfigDto activeConfig(RabbitMqIntegrationSettings settings) {
        return new ActiveRabbitMqConfigDto(
            settings.provider(),
            settings.status(),
            runtimeHealthProbe.connectionStatus(),
            settings.baseUrl(),
            settings.username(),
            settings.virtualHost(),
            format(settings.lastCheckedAt()),
            settings.lastError(),
            format(settings.updatedAt()),
            configVersion(settings),
            "Testing a connection does not switch the active configuration; save integration settings to take effect."
        );
    }

    private String configVersion(RabbitMqIntegrationSettings settings) {
        if (settings == null || settings.updatedAt() == null) {
            return "runtime-default";
        }
        return "cfg-" + settings.updatedAt().format(VERSION_FORMATTER);
    }

    private RabbitMqTopologyDto topology() {
        return new RabbitMqTopologyDto(
            properties.getExchange(),
            properties.getQueue(),
            properties.getRoutingKey(),
            properties.getDeadLetterExchange(),
            properties.getDeadLetterQueue(),
            properties.getDeadLetterRoutingKey()
        );
    }

    private List<MessageQueueMetricDto> metrics(MessageQueueHealthSummary summary) {
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
            new MessageQueueMetricDto("Publish failed", String.valueOf(publishFailed), "Waiting for compensation", publishFailed > 0 ? "trend danger" : "trend", "red"),
            new MessageQueueMetricDto("Execution timeout", String.valueOf(executionTimeout), "Review lease expired", executionTimeout > 0 ? "trend danger" : "trend", "orange"),
            new MessageQueueMetricDto("Requeue pending", String.valueOf(requeuePending), "Execution recovery publishing", requeuePending > 0 ? "trend danger" : "trend", "orange"),
            new MessageQueueMetricDto("Compensating", String.valueOf(claimed), "Claimed by workers", claimed > 0 ? "trend danger" : "trend", "orange"),
            new MessageQueueMetricDto("DLQ backlog", String.valueOf(dlqBacklog), "Database observed status", dlqBacklog > 0 ? "trend danger" : "trend", "red")
        );
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

    private RetryCompensationStatusDto retryCompensation(MessageQueueHealthSummary summary, String latestFailureReason) {
        return new RetryCompensationStatusDto(
            maxAttempts(),
            Math.max(1000, properties.getPublishCompensationIntervalMs()),
            Math.max(1, properties.getPublishCompensationBatchSize()),
            Math.max(1000, properties.getPublishCompensationLeaseMs()),
            safeCount(summary == null ? null : summary.getClaimed()),
            null,
            latestFailureReason
        );
    }

    private long safeCount(Long value) {
        return value == null ? 0L : value;
    }

    private int maxAttempts() {
        return Math.max(1, properties.getPublishCompensationMaxAttempts());
    }

    private String format(LocalDateTime value) {
        return value == null ? null : value.format(DATE_TIME_FORMATTER);
    }
}
