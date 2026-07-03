package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.config.RabbitMqIntegrationProvider;
import com.repoguard.agent.config.RabbitMqIntegrationSettings;
import com.repoguard.agent.config.RabbitReviewQueueProperties;
import com.repoguard.agent.dto.MessageQueueRequeueResponse;
import com.repoguard.agent.dto.MessageQueueHealthResponse;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.ChannelCallback;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

class MessageQueueHealthServiceImplTest {

    private final ReviewTaskMapper reviewTaskMapper = org.mockito.Mockito.mock(ReviewTaskMapper.class);
    private final ReviewTimelineMapper reviewTimelineMapper = org.mockito.Mockito.mock(ReviewTimelineMapper.class);
    private final SystemSettingLogMapper systemSettingLogMapper = org.mockito.Mockito.mock(SystemSettingLogMapper.class);
    private final RabbitMqIntegrationProvider rabbitMqIntegrationProvider = org.mockito.Mockito.mock(RabbitMqIntegrationProvider.class);
    private final RabbitReviewQueueProperties properties = new RabbitReviewQueueProperties();
    private final RabbitTemplate rabbitTemplate = org.mockito.Mockito.mock(RabbitTemplate.class);
    private final ReviewTaskPublisher reviewTaskPublisher = org.mockito.Mockito.mock(ReviewTaskPublisher.class);
    private final MessageQueueHealthServiceImpl service = new MessageQueueHealthServiceImpl(
        reviewTaskMapper,
        reviewTimelineMapper,
        systemSettingLogMapper,
        rabbitMqIntegrationProvider,
        properties,
        rabbitTemplate,
        reviewTaskPublisher
    );

    @Test
    void healthSummarizesActiveConfigTopologyAndExceptionTasks() {
        properties.setExchange("repoguard.review.exchange.v2");
        properties.setQueue("repoguard.review.queue.v2");
        properties.setPublishCompensationMaxAttempts(3);
        properties.setPublishCompensationIntervalMs(60000);
        properties.setPublishCompensationBatchSize(20);
        properties.setPublishCompensationLeaseMs(120000);

        when(rabbitMqIntegrationProvider.getSettings()).thenReturn(rabbitSettings());
        when(rabbitTemplate.execute(org.mockito.ArgumentMatchers.<ChannelCallback<Boolean>>any())).thenReturn(true);
        when(reviewTaskMapper.selectMessageQueueHealthSummary()).thenReturn(summary(5L, 3L, 1L, 1L));
        when(reviewTaskMapper.selectLatestPublishFailureReason()).thenReturn("routing failed");
        when(reviewTaskMapper.selectMessageQueueExceptionTasks()).thenReturn(List.of(
            task(1L, "QUEUED", 0, null, null, null, LocalDateTime.of(2026, 6, 10, 20, 0)),
            task(2L, "PUBLISH_FAILED", 2, LocalDateTime.of(2026, 6, 10, 21, 10), null, "publisher confirm timed out", LocalDateTime.of(2026, 6, 10, 21, 0)),
            task(3L, "PUBLISH_FAILED", 1, LocalDateTime.of(2026, 6, 10, 21, 12), "repoguard-a1", "broker unavailable", LocalDateTime.of(2026, 6, 10, 21, 1)),
            task(4L, "PUBLISH_FAILED", 3, null, null, "max attempts reached", LocalDateTime.of(2026, 6, 10, 21, 2)),
            task(5L, "DLQ", 3, null, null, "routing failed", LocalDateTime.of(2026, 6, 10, 21, 3))
        ));

        MessageQueueHealthResponse health = service.getHealth();
        Map<String, String> metricValues = health.metrics().stream()
            .collect(Collectors.toMap(metric -> metric.label(), metric -> metric.value()));

        assertThat(health.activeConfig().provider()).isEqualTo("RABBITMQ");
        assertThat(health.activeConfig().runtimeConnectionStatus()).isEqualTo("CONNECTED");
        assertThat(health.activeConfig().configVersion()).isEqualTo("cfg-20260610-205800");
        assertThat(health.activeConfig().switchNotice()).contains("does not switch");
        assertThat(health.topology().queue()).isEqualTo("repoguard.review.queue.v2");
        assertThat(metricValues).containsEntry("Publish succeeded", "1");
        assertThat(metricValues).containsEntry("Publish failed", "3");
        assertThat(metricValues).containsEntry("Compensating", "1");
        assertThat(metricValues).containsEntry("DLQ backlog", "1");
        assertThat(health.retryCompensation().maxAttempts()).isEqualTo(3);
        assertThat(health.retryCompensation().claimedTaskCount()).isEqualTo(1);
        assertThat(health.retryCompensation().latestFailureReason()).isEqualTo("routing failed");
        assertThat(health.exceptionTasks()).hasSize(4);
        assertThat(health.exceptionTasks().get(0).status()).isEqualTo("DLQ");
        assertThat(health.exceptionTasks()).anyMatch(task -> "RETRY_EXHAUSTED".equals(task.status()));
        assertThat(health.exceptionTasks()).anyMatch(task -> "PUBLISH_CLAIMED".equals(task.status()));
        assertThat(health.dataSource()).isEqualTo("DATABASE_TASK_STATE");
        verify(reviewTaskMapper, never()).selectList(any());
    }

    @Test
    void healthReturnsDisconnectedWhenRuntimeProbeTimesOut() {
        properties.setHealthCheckTimeoutMs(50);
        when(rabbitMqIntegrationProvider.getSettings()).thenReturn(rabbitSettings());
        when(reviewTaskMapper.selectMessageQueueHealthSummary()).thenReturn(summary(0L, 0L, 0L, 0L));
        when(reviewTaskMapper.selectMessageQueueExceptionTasks()).thenReturn(List.of());
        when(rabbitTemplate.execute(org.mockito.ArgumentMatchers.<ChannelCallback<Boolean>>any())).thenAnswer(invocation -> {
            Thread.sleep(500);
            return true;
        });

        long startedAt = System.nanoTime();
        MessageQueueHealthResponse health = service.getHealth();
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        assertThat(health.activeConfig().runtimeConnectionStatus()).isEqualTo("DISCONNECTED");
        assertThat(elapsedMs).isLessThan(450);
    }

    @Test
    void requeueTaskPublishesMessageAndMarksTaskQueued() {
        ReviewTask task = task(42L, "PUBLISH_FAILED", 3, LocalDateTime.of(2026, 6, 11, 10, 0), null, "max attempts", LocalDateTime.of(2026, 6, 11, 9, 0));
        ReviewTimeline latest = new ReviewTimeline();
        latest.setSortOrder(4);
        when(reviewTaskMapper.selectById(42L)).thenReturn(task);
        when(reviewTimelineMapper.selectOne(any())).thenReturn(latest);

        MessageQueueRequeueResponse response = service.requeueTask(42L);

        assertThat(response.status()).isEqualTo("queued");
        assertThat(task.getStatus()).isEqualTo("QUEUED");
        assertThat(task.getLlmStatus()).isEqualTo("PENDING");
        assertThat(task.getPublishAttempts()).isZero();
        assertThat(task.getNextPublishRetryAt()).isNull();
        assertThat(task.getLastPublishError()).isNull();
        verify(reviewTaskMapper).updateById(task);
        verify(reviewTaskPublisher).publish(any(ReviewTaskMessage.class));
        verify(reviewTimelineMapper).insert(any(ReviewTimeline.class));
        verify(systemSettingLogMapper).insert(any(SystemSettingLog.class));
    }

    @Test
    void requeueTaskPublishesOnlyAfterQueuedStateTransactionCommits() {
        RecordingTransactionManager transactionManager = new RecordingTransactionManager();
        MessageQueueHealthServiceImpl transactionalService = new MessageQueueHealthServiceImpl(
            reviewTaskMapper,
            reviewTimelineMapper,
            systemSettingLogMapper,
            rabbitMqIntegrationProvider,
            properties,
            rabbitTemplate,
            reviewTaskPublisher,
            transactionManager,
            null,
            null
        );
        ReviewTask task = task(42L, "PUBLISH_FAILED", 3, LocalDateTime.of(2026, 6, 11, 10, 0), null, "max attempts", LocalDateTime.of(2026, 6, 11, 9, 0));
        ReviewTimeline latest = new ReviewTimeline();
        latest.setSortOrder(4);
        when(reviewTaskMapper.selectById(42L)).thenReturn(task);
        when(reviewTimelineMapper.selectOne(any())).thenReturn(latest);
        doAnswer(invocation -> {
            assertThat(transactionManager.committed).isTrue();
            return null;
        }).when(reviewTaskPublisher).publish(any(ReviewTaskMessage.class));

        MessageQueueRequeueResponse response = transactionalService.requeueTask(42L);

        assertThat(response.status()).isEqualTo("queued");
        assertThat(task.getStatus()).isEqualTo("QUEUED");
        verify(reviewTaskPublisher).publish(any(ReviewTaskMessage.class));
    }

    @Test
    void requeueTaskRestoresPublishFailedWhenPublishFailsAgain() {
        properties.setPublishCompensationIntervalMs(1000);
        ReviewTask task = task(42L, "PUBLISH_FAILED", 3, LocalDateTime.of(2026, 6, 11, 10, 0), null, "max attempts", LocalDateTime.of(2026, 6, 11, 9, 0));
        when(reviewTaskMapper.selectById(42L)).thenReturn(task);
        doThrow(new MessagePublishException("publisher confirm timed out password=raw-password token=raw-token"))
            .when(reviewTaskPublisher)
            .publish(any(ReviewTaskMessage.class));

        MessageQueueRequeueResponse response = service.requeueTask(42L);

        assertThat(response.status()).isEqualTo("publish_failed");
        assertThat(task.getStatus()).isEqualTo("PUBLISH_FAILED");
        assertThat(task.getLlmStatus()).isEqualTo("PENDING");
        assertThat(task.getPublishAttempts()).isEqualTo(1);
        assertThat(task.getNextPublishRetryAt()).isNotNull();
        assertThat(task.getLastPublishError()).contains("publisher confirm timed out");
        assertThat(task.getLastPublishError()).contains("password=****", "token=****");
        assertThat(task.getLastPublishError()).doesNotContain("raw-password", "raw-token");
        verify(systemSettingLogMapper).insert(any(SystemSettingLog.class));
    }

    @Test
    void requeueTaskRejectsClaimedTask() {
        ReviewTask task = task(42L, "PUBLISH_FAILED", 1, null, "repoguard-a1", "broker unavailable", LocalDateTime.of(2026, 6, 11, 9, 0));
        when(reviewTaskMapper.selectById(42L)).thenReturn(task);

        assertThatThrownBy(() -> service.requeueTask(42L))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Claimed message tasks cannot be requeued");
        verify(systemSettingLogMapper).insert(any(SystemSettingLog.class));
    }

    private RabbitMqIntegrationSettings rabbitSettings() {
        return new RabbitMqIntegrationSettings(
            "RABBITMQ",
            "CONNECTED",
            "amqp://localhost:5672",
            "repoguard",
            "/",
            LocalDateTime.of(2026, 6, 10, 21, 2, 12),
            null,
            LocalDateTime.of(2026, 6, 10, 20, 58)
        );
    }

    private MessageQueueHealthSummary summary(Long total, Long publishFailed, Long claimed, Long dlqBacklog) {
        MessageQueueHealthSummary summary = new MessageQueueHealthSummary();
        summary.setTotal(total);
        summary.setPublishFailed(publishFailed);
        summary.setClaimed(claimed);
        summary.setDlqBacklog(dlqBacklog);
        summary.setLatestFailureCreatedAt(LocalDateTime.of(2026, 6, 10, 21, 3));
        return summary;
    }

    private ReviewTask task(
        Long id,
        String status,
        Integer publishAttempts,
        LocalDateTime nextRetryAt,
        String claimedBy,
        String lastError,
        LocalDateTime createdAt
    ) {
        ReviewTask task = new ReviewTask();
        task.setId(id);
        task.setOrganization("cocojiu");
        task.setRepository("PRAgent");
        task.setPrNumber(100 + id.intValue());
        task.setStatus(status);
        task.setLlmStatus("FAILED");
        task.setPublishAttempts(publishAttempts);
        task.setNextPublishRetryAt(nextRetryAt);
        task.setPublishClaimedBy(claimedBy);
        task.setPublishClaimedAt(claimedBy == null ? null : LocalDateTime.of(2026, 6, 10, 21, 2));
        task.setLastPublishError(lastError);
        task.setCreatedAt(createdAt);
        return task;
    }

    private static class RecordingTransactionManager extends AbstractPlatformTransactionManager {
        private boolean committed;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            committed = false;
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            committed = true;
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            committed = false;
        }
    }
}
