package com.repoguard.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.config.RabbitMqIntegrationProvider;
import com.repoguard.agent.config.RabbitMqIntegrationSettings;
import com.repoguard.agent.config.RabbitReviewQueueProperties;
import com.repoguard.agent.dto.ActiveRabbitMqConfigDto;
import com.repoguard.agent.dto.MessageQueueExceptionTaskDto;
import com.repoguard.agent.dto.MessageQueueHealthResponse;
import com.repoguard.agent.dto.MessageQueueMetricDto;
import com.repoguard.agent.dto.MessageQueueRequeueResponse;
import com.repoguard.agent.dto.RabbitMqTopologyDto;
import com.repoguard.agent.dto.RetryCompensationStatusDto;
import com.repoguard.agent.entity.ReviewTimeline;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.entity.SystemSettingLog;
import com.repoguard.agent.mapper.ReviewTimelineMapper;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.mapper.ReviewTaskMapper.MessageQueueHealthSummary;
import com.repoguard.agent.mapper.SystemSettingLogMapper;
import com.repoguard.agent.messaging.MessagePublishException;
import com.repoguard.agent.messaging.ReviewTaskMessage;
import com.repoguard.agent.messaging.ReviewTaskPublisher;
import com.repoguard.agent.observability.LogContext;
import com.repoguard.agent.observability.RepoGuardMetrics;
import com.repoguard.agent.review.LlmStatus;
import com.repoguard.agent.review.ReviewTaskStateMachine;
import com.repoguard.agent.service.MessageQueueHealthService;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class MessageQueueHealthServiceImpl implements MessageQueueHealthService {

    private static final String STATUS_DLQ = "DLQ";
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter VERSION_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final ReviewTaskMapper reviewTaskMapper;
    private final ReviewTimelineMapper reviewTimelineMapper;
    private final SystemSettingLogMapper systemSettingLogMapper;
    private final RabbitMqIntegrationProvider rabbitMqIntegrationProvider;
    private final RabbitReviewQueueProperties properties;
    private final RabbitTemplate rabbitTemplate;
    private final ReviewTaskPublisher reviewTaskPublisher;
    private final RepoGuardMetrics metrics;
    private final ReviewTaskStateMachine reviewTaskStateMachine;
    private final TransactionTemplate transactionTemplate;

    public MessageQueueHealthServiceImpl(
        ReviewTaskMapper reviewTaskMapper,
        ReviewTimelineMapper reviewTimelineMapper,
        SystemSettingLogMapper systemSettingLogMapper,
        RabbitMqIntegrationProvider rabbitMqIntegrationProvider,
        RabbitReviewQueueProperties properties,
        RabbitTemplate rabbitTemplate,
        ReviewTaskPublisher reviewTaskPublisher
    ) {
        this(
            reviewTaskMapper,
            reviewTimelineMapper,
            systemSettingLogMapper,
            rabbitMqIntegrationProvider,
            properties,
            rabbitTemplate,
            reviewTaskPublisher,
            null,
            null,
            null
        );
    }

    @Autowired
    public MessageQueueHealthServiceImpl(
        ReviewTaskMapper reviewTaskMapper,
        ReviewTimelineMapper reviewTimelineMapper,
        SystemSettingLogMapper systemSettingLogMapper,
        RabbitMqIntegrationProvider rabbitMqIntegrationProvider,
        RabbitReviewQueueProperties properties,
        RabbitTemplate rabbitTemplate,
        ReviewTaskPublisher reviewTaskPublisher,
        PlatformTransactionManager transactionManager,
        ReviewTaskStateMachine reviewTaskStateMachine
    ) {
        this(
            reviewTaskMapper,
            reviewTimelineMapper,
            systemSettingLogMapper,
            rabbitMqIntegrationProvider,
            properties,
            rabbitTemplate,
            reviewTaskPublisher,
            transactionManager,
            null,
            reviewTaskStateMachine
        );
    }

    public MessageQueueHealthServiceImpl(
        ReviewTaskMapper reviewTaskMapper,
        ReviewTimelineMapper reviewTimelineMapper,
        SystemSettingLogMapper systemSettingLogMapper,
        RabbitMqIntegrationProvider rabbitMqIntegrationProvider,
        RabbitReviewQueueProperties properties,
        RabbitTemplate rabbitTemplate,
        ReviewTaskPublisher reviewTaskPublisher,
        RepoGuardMetrics metrics
    ) {
        this(
            reviewTaskMapper,
            reviewTimelineMapper,
            systemSettingLogMapper,
            rabbitMqIntegrationProvider,
            properties,
            rabbitTemplate,
            reviewTaskPublisher,
            null,
            metrics,
            null
        );
    }

    public MessageQueueHealthServiceImpl(
        ReviewTaskMapper reviewTaskMapper,
        ReviewTimelineMapper reviewTimelineMapper,
        SystemSettingLogMapper systemSettingLogMapper,
        RabbitMqIntegrationProvider rabbitMqIntegrationProvider,
        RabbitReviewQueueProperties properties,
        RabbitTemplate rabbitTemplate,
        ReviewTaskPublisher reviewTaskPublisher,
        PlatformTransactionManager transactionManager,
        RepoGuardMetrics metrics,
        ReviewTaskStateMachine reviewTaskStateMachine
    ) {
        this.reviewTaskMapper = reviewTaskMapper;
        this.reviewTimelineMapper = reviewTimelineMapper;
        this.systemSettingLogMapper = systemSettingLogMapper;
        this.rabbitMqIntegrationProvider = rabbitMqIntegrationProvider;
        this.properties = properties;
        this.rabbitTemplate = rabbitTemplate;
        this.reviewTaskPublisher = reviewTaskPublisher;
        this.metrics = metrics;
        this.reviewTaskStateMachine = reviewTaskStateMachine == null
            ? new ReviewTaskStateMachine()
            : reviewTaskStateMachine;
        this.transactionTemplate = transactionManager == null ? null : new TransactionTemplate(transactionManager);
    }

    @Override
    public MessageQueueHealthResponse getHealth() {
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
            exceptionTasks(exceptionTasks),
            format(LocalDateTime.now()),
            "DATABASE_TASK_STATE"
        );
    }

    @Override
    public MessageQueueRequeueResponse requeueTask(Long taskId) {
        RequeuePublishContext context = executeInTransaction(() -> prepareRequeue(taskId));

        try {
            reviewTaskPublisher.publish(context.message());
            recordAudit(context.taskId(), "SUCCESS", "queued");
            return new MessageQueueRequeueResponse(context.taskId(), "queued", "Message task requeued", context.publishAttempts());
        } catch (MessagePublishException ex) {
            executeInTransaction(() -> {
                ReviewTask failedTask = reviewTaskMapper.selectById(context.taskId());
                if (failedTask != null) {
                    markPublishFailed(failedTask, ex, context.queuedAt());
                }
                return null;
            });
            recordAudit(context.taskId(), "FAILED", truncate(errorMessage(ex)));
            return new MessageQueueRequeueResponse(
                context.taskId(),
                "publish_failed",
                "Message task saved, waiting for publish compensation",
                context.publishAttempts() + 1
            );
        }
    }

    private RequeuePublishContext prepareRequeue(Long taskId) {
        ReviewTask task = reviewTaskMapper.selectById(taskId);
        if (task == null) {
            recordAudit(taskId, "FAILED", "not found");
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND, "Review task not found: " + taskId);
        }
        try {
            reviewTaskStateMachine.ensurePublishRequeueAllowed(task.getStatus(), task.getPublishClaimedAt() != null);
        } catch (BusinessException ex) {
            if (task.getPublishClaimedAt() != null) {
                recordAudit(taskId, "FAILED", "claimedBy=" + task.getPublishClaimedBy());
            } else {
                recordAudit(taskId, "FAILED", "status=" + task.getStatus());
            }
            throw ex;
        }

        LocalDateTime queuedAt = LocalDateTime.now();
        task.setStatus(reviewTaskStateMachine.statusWhenQueued());
        task.setLlmStatus(LlmStatus.PENDING.code());
        task.setPublishAttempts(0);
        task.setNextPublishRetryAt(null);
        task.setLastPublishError(null);
        task.setPublishClaimedAt(null);
        task.setPublishClaimedBy(null);
        reviewTaskMapper.updateById(task);
        appendTimeline(task.getId(), "Message manually requeued", queuedAt, "CURRENT");

        return new RequeuePublishContext(
            task.getId(),
            task.getPublishAttempts(),
            queuedAt,
            new ReviewTaskMessage(
                task.getId(),
                task.getOrganization(),
                task.getRepository(),
                task.getPrNumber(),
                task.getCommitSha(),
                queuedAt,
                LogContext.currentTraceId()
            )
        );
    }

    private <T> T executeInTransaction(TransactionCallback<T> callback) {
        if (transactionTemplate == null) {
            return callback.execute();
        }
        return transactionTemplate.execute(status -> callback.execute());
    }

    private ActiveRabbitMqConfigDto activeConfig(RabbitMqIntegrationSettings settings) {
        return new ActiveRabbitMqConfigDto(
            settings.provider(),
            settings.status(),
            runtimeConnectionStatus(),
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

    private String runtimeConnectionStatus() {
        CompletableFuture<Boolean> probe = CompletableFuture.supplyAsync(
            () -> rabbitTemplate.execute(channel -> channel.isOpen())
        );
        try {
            Boolean open = probe.get(runtimeConnectionTimeoutMs(), TimeUnit.MILLISECONDS);
            return Boolean.TRUE.equals(open) ? "CONNECTED" : "DISCONNECTED";
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return "DISCONNECTED";
        } catch (ExecutionException | RuntimeException | TimeoutException ex) {
            probe.cancel(true);
            return "DISCONNECTED";
        }
    }

    private long runtimeConnectionTimeoutMs() {
        return Math.max(100, properties.getHealthCheckTimeoutMs());
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
        long claimed = safeCount(summary == null ? null : summary.getClaimed());
        long dlqBacklog = safeCount(summary == null ? null : summary.getDlqBacklog());
        long publishSucceeded = Math.max(0, total - publishFailed - dlqBacklog);
        recordQueueDepth(publishFailed, claimed, dlqBacklog);

        return List.of(
            new MessageQueueMetricDto("Publish succeeded", String.valueOf(publishSucceeded), "Current active config", "trend", "blue"),
            new MessageQueueMetricDto("Publish failed", String.valueOf(publishFailed), "Waiting for compensation", publishFailed > 0 ? "trend danger" : "trend", "red"),
            new MessageQueueMetricDto("Compensating", String.valueOf(claimed), "Claimed by workers", claimed > 0 ? "trend danger" : "trend", "orange"),
            new MessageQueueMetricDto("DLQ backlog", String.valueOf(dlqBacklog), "Database observed status", dlqBacklog > 0 ? "trend danger" : "trend", "red")
        );
    }

    private void recordQueueDepth(long publishFailed, long claimed, long dlqBacklog) {
        if (metrics == null) {
            return;
        }
        metrics.rabbitQueueDepth(properties.getQueue(), "publish_failed", publishFailed);
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

    private long safeCount(Long value) {
        return value == null ? 0L : value;
    }

    private boolean isExceptionTask(ReviewTask task) {
        return isPublishFailed(task) || STATUS_DLQ.equals(task.getStatus());
    }

    private void appendTimeline(Long taskId, String label, LocalDateTime eventTime, String status) {
        reviewTimelineMapper.update(
            new UpdateWrapper<ReviewTimeline>()
                .eq("task_id", taskId)
                .eq("status", "CURRENT")
                .set("status", "DONE")
        );

        ReviewTimeline timeline = new ReviewTimeline();
        timeline.setTaskId(taskId);
        timeline.setLabel(truncate(label));
        timeline.setEventTime(eventTime);
        timeline.setStatus(status);
        timeline.setSortOrder(nextTimelineSortOrder(taskId));
        reviewTimelineMapper.insert(timeline);
    }

    private void markPublishFailed(ReviewTask task, MessagePublishException ex, LocalDateTime failedAt) {
        task.setStatus(reviewTaskStateMachine.statusWhenPublishFailed());
        task.setLlmStatus(LlmStatus.PENDING.code());
        task.setPublishAttempts(safeAttempts(task) + 1);
        task.setNextPublishRetryAt(failedAt.plusNanos(Math.max(1000, properties.getPublishCompensationIntervalMs()) * 1_000_000));
        task.setLastPublishError(truncate(errorMessage(ex)));
        task.setPublishClaimedAt(null);
        task.setPublishClaimedBy(null);
        reviewTaskMapper.updateById(task);
        appendTimeline(task.getId(), "Message manual requeue failed: " + errorMessage(ex), failedAt, "FAILED");
    }

    private void recordAudit(Long taskId, String status, String detail) {
        SystemSettingLog log = new SystemSettingLog();
        log.setOperator("admin-api-key");
        log.setAction(truncate("MQ requeue task #" + taskId + ": " + detail));
        log.setStatus(status);
        log.setCreatedAt(LocalDateTime.now());
        systemSettingLogMapper.insert(log);
    }

    private int nextTimelineSortOrder(Long taskId) {
        ReviewTimeline latest = reviewTimelineMapper.selectOne(
            new LambdaQueryWrapper<ReviewTimeline>()
                .eq(ReviewTimeline::getTaskId, taskId)
                .orderByDesc(ReviewTimeline::getSortOrder)
                .last("limit 1")
        );
        return latest == null || latest.getSortOrder() == null ? 1 : latest.getSortOrder() + 1;
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
        return reviewTaskStateMachine.isPublishFailed(task.getStatus());
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

    private String errorMessage(Exception ex) {
        return ex.getMessage() == null || ex.getMessage().isBlank()
            ? ex.getClass().getSimpleName()
            : ex.getMessage().replaceAll("\\s+", " ").trim();
    }

    private String truncate(String value) {
        return value.length() > 120 ? value.substring(0, 117) + "..." : value;
    }

    private String format(LocalDateTime value) {
        return value == null ? null : value.format(DATE_TIME_FORMATTER);
    }

    private record RequeuePublishContext(
        Long taskId,
        int publishAttempts,
        LocalDateTime queuedAt,
        ReviewTaskMessage message
    ) {
    }

    @FunctionalInterface
    private interface TransactionCallback<T> {
        T execute();
    }
}
