package com.repoguard.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.repoguard.agent.config.RabbitReviewQueueProperties;
import com.repoguard.agent.dto.ActiveRabbitMqConfigDto;
import com.repoguard.agent.dto.MessageQueueExceptionTaskDto;
import com.repoguard.agent.dto.MessageQueueHealthResponse;
import com.repoguard.agent.dto.MessageQueueMetricDto;
import com.repoguard.agent.dto.RabbitMqTopologyDto;
import com.repoguard.agent.dto.RetryCompensationStatusDto;
import com.repoguard.agent.entity.IntegrationConfig;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.IntegrationConfigMapper;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.service.MessageQueueHealthService;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

@Service
public class MessageQueueHealthServiceImpl implements MessageQueueHealthService {

    private static final String RABBITMQ_PROVIDER = "RABBITMQ";
    private static final String STATUS_PUBLISH_FAILED = "PUBLISH_FAILED";
    private static final String STATUS_DLQ = "DLQ";
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter VERSION_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final ReviewTaskMapper reviewTaskMapper;
    private final IntegrationConfigMapper integrationConfigMapper;
    private final RabbitReviewQueueProperties properties;
    private final RabbitTemplate rabbitTemplate;

    public MessageQueueHealthServiceImpl(
        ReviewTaskMapper reviewTaskMapper,
        IntegrationConfigMapper integrationConfigMapper,
        RabbitReviewQueueProperties properties,
        RabbitTemplate rabbitTemplate
    ) {
        this.reviewTaskMapper = reviewTaskMapper;
        this.integrationConfigMapper = integrationConfigMapper;
        this.properties = properties;
        this.rabbitTemplate = rabbitTemplate;
    }

    @Override
    public MessageQueueHealthResponse getHealth() {
        List<ReviewTask> tasks = reviewTaskMapper.selectList(
            new LambdaQueryWrapper<ReviewTask>().orderByDesc(ReviewTask::getCreatedAt)
        );
        IntegrationConfig activeConfig = integrationConfigMapper.selectOne(
            new LambdaQueryWrapper<IntegrationConfig>().eq(IntegrationConfig::getProvider, RABBITMQ_PROVIDER)
        );

        return new MessageQueueHealthResponse(
            activeConfig(activeConfig),
            topology(),
            metrics(tasks),
            retryCompensation(tasks),
            exceptionTasks(tasks),
            format(LocalDateTime.now()),
            "DATABASE_TASK_STATE"
        );
    }

    private ActiveRabbitMqConfigDto activeConfig(IntegrationConfig config) {
        return new ActiveRabbitMqConfigDto(
            RABBITMQ_PROVIDER,
            config == null ? "NOT_CONFIGURED" : config.getStatus(),
            runtimeConnectionStatus(),
            config == null ? null : config.getBaseUrl(),
            config == null ? null : config.getDefaultOwner(),
            config == null ? null : config.getDefaultRepo(),
            config == null ? null : format(config.getLastCheckedAt()),
            config == null ? null : config.getLastError(),
            config == null ? null : format(config.getUpdatedAt()),
            configVersion(config),
            "Testing a connection does not switch the active configuration; save integration settings to take effect."
        );
    }

    private String configVersion(IntegrationConfig config) {
        if (config == null || config.getUpdatedAt() == null) {
            return "runtime-default";
        }
        return "cfg-" + config.getUpdatedAt().format(VERSION_FORMATTER);
    }

    private String runtimeConnectionStatus() {
        try {
            Boolean open = rabbitTemplate.execute(channel -> channel.isOpen());
            return Boolean.TRUE.equals(open) ? "CONNECTED" : "DISCONNECTED";
        } catch (RuntimeException ex) {
            return "DISCONNECTED";
        }
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

    private List<MessageQueueMetricDto> metrics(List<ReviewTask> tasks) {
        long publishFailed = tasks.stream().filter(this::isPublishFailed).count();
        long claimed = tasks.stream().filter(task -> task.getPublishClaimedAt() != null).count();
        long dlqBacklog = tasks.stream().filter(task -> STATUS_DLQ.equals(task.getStatus())).count();
        long publishSucceeded = Math.max(0, tasks.size() - publishFailed - dlqBacklog);

        return List.of(
            new MessageQueueMetricDto("Publish succeeded", String.valueOf(publishSucceeded), "Current active config", "trend", "blue"),
            new MessageQueueMetricDto("Publish failed", String.valueOf(publishFailed), "Waiting for compensation", publishFailed > 0 ? "trend danger" : "trend", "red"),
            new MessageQueueMetricDto("Compensating", String.valueOf(claimed), "Claimed by workers", claimed > 0 ? "trend danger" : "trend", "orange"),
            new MessageQueueMetricDto("DLQ backlog", String.valueOf(dlqBacklog), "Database observed status", dlqBacklog > 0 ? "trend danger" : "trend", "red")
        );
    }

    private RetryCompensationStatusDto retryCompensation(List<ReviewTask> tasks) {
        long claimed = tasks.stream().filter(task -> task.getPublishClaimedAt() != null).count();
        String latestFailureReason = tasks.stream()
            .filter(task -> task.getLastPublishError() != null && !task.getLastPublishError().isBlank())
            .max(Comparator.comparing(ReviewTask::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
            .map(ReviewTask::getLastPublishError)
            .orElse(null);

        return new RetryCompensationStatusDto(
            maxAttempts(),
            Math.max(1000, properties.getPublishCompensationIntervalMs()),
            Math.max(1, properties.getPublishCompensationBatchSize()),
            Math.max(1000, properties.getPublishCompensationLeaseMs()),
            claimed,
            null,
            latestFailureReason
        );
    }

    private List<MessageQueueExceptionTaskDto> exceptionTasks(List<ReviewTask> tasks) {
        return tasks.stream()
            .filter(this::isExceptionTask)
            .sorted(Comparator.comparing(ReviewTask::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
            .limit(20)
            .map(task -> new MessageQueueExceptionTaskDto(
                task.getId(),
                task.getOrganization(),
                task.getRepository(),
                task.getPrNumber(),
                exceptionStatus(task),
                task.getPublishAttempts(),
                format(task.getNextPublishRetryAt()),
                task.getPublishClaimedBy(),
                format(task.getPublishClaimedAt()),
                task.getLastPublishError()
            ))
            .toList();
    }

    private boolean isExceptionTask(ReviewTask task) {
        return isPublishFailed(task) || STATUS_DLQ.equals(task.getStatus());
    }

    private String exceptionStatus(ReviewTask task) {
        if (STATUS_DLQ.equals(task.getStatus())) {
            return STATUS_DLQ;
        }
        if (isRetryExhausted(task)) {
            return "RETRY_EXHAUSTED";
        }
        if (task.getPublishClaimedAt() != null) {
            return "PUBLISH_CLAIMED";
        }
        return task.getStatus();
    }

    private boolean isPublishFailed(ReviewTask task) {
        return STATUS_PUBLISH_FAILED.equals(task.getStatus());
    }

    private boolean isRetryExhausted(ReviewTask task) {
        return isPublishFailed(task) && safeAttempts(task) >= maxAttempts();
    }

    private int safeAttempts(ReviewTask task) {
        return task.getPublishAttempts() == null ? 0 : task.getPublishAttempts();
    }

    private int maxAttempts() {
        return Math.max(1, properties.getPublishCompensationMaxAttempts());
    }

    private String format(LocalDateTime value) {
        return value == null ? null : value.format(DATE_TIME_FORMATTER);
    }
}
