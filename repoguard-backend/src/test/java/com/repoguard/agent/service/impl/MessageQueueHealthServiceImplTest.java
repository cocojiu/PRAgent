package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.repoguard.agent.config.RabbitReviewQueueProperties;
import com.repoguard.agent.dto.MessageQueueHealthResponse;
import com.repoguard.agent.entity.IntegrationConfig;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.IntegrationConfigMapper;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.ChannelCallback;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class MessageQueueHealthServiceImplTest {

    private final ReviewTaskMapper reviewTaskMapper = org.mockito.Mockito.mock(ReviewTaskMapper.class);
    private final IntegrationConfigMapper integrationConfigMapper = org.mockito.Mockito.mock(IntegrationConfigMapper.class);
    private final RabbitReviewQueueProperties properties = new RabbitReviewQueueProperties();
    private final RabbitTemplate rabbitTemplate = org.mockito.Mockito.mock(RabbitTemplate.class);
    private final MessageQueueHealthServiceImpl service = new MessageQueueHealthServiceImpl(
        reviewTaskMapper,
        integrationConfigMapper,
        properties,
        rabbitTemplate
    );

    @Test
    void healthSummarizesActiveConfigTopologyAndExceptionTasks() {
        properties.setExchange("repoguard.review.exchange.v2");
        properties.setQueue("repoguard.review.queue.v2");
        properties.setPublishCompensationMaxAttempts(3);
        properties.setPublishCompensationIntervalMs(60000);
        properties.setPublishCompensationBatchSize(20);
        properties.setPublishCompensationLeaseMs(120000);

        when(integrationConfigMapper.selectOne(any())).thenReturn(rabbitConfig());
        when(rabbitTemplate.execute(org.mockito.ArgumentMatchers.<ChannelCallback<Boolean>>any())).thenReturn(true);
        when(reviewTaskMapper.selectList(any())).thenReturn(List.of(
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
    }

    private IntegrationConfig rabbitConfig() {
        IntegrationConfig config = new IntegrationConfig();
        config.setProvider("RABBITMQ");
        config.setStatus("CONNECTED");
        config.setBaseUrl("amqp://localhost:5672");
        config.setDefaultOwner("repoguard");
        config.setDefaultRepo("/");
        config.setLastCheckedAt(LocalDateTime.of(2026, 6, 10, 21, 2, 12));
        config.setUpdatedAt(LocalDateTime.of(2026, 6, 10, 20, 58));
        return config;
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
        task.setPublishAttempts(publishAttempts);
        task.setNextPublishRetryAt(nextRetryAt);
        task.setPublishClaimedBy(claimedBy);
        task.setPublishClaimedAt(claimedBy == null ? null : LocalDateTime.of(2026, 6, 10, 21, 2));
        task.setLastPublishError(lastError);
        task.setCreatedAt(createdAt);
        return task;
    }
}
