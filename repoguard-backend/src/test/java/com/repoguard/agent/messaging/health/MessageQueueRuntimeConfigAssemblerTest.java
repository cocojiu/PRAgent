package com.repoguard.agent.messaging.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.repoguard.agent.messaging.RabbitMqIntegrationSettings;
import com.repoguard.agent.config.RabbitReviewQueueProperties;
import com.repoguard.agent.dto.ActiveRabbitMqConfigDto;
import com.repoguard.agent.dto.RabbitMqTopologyDto;
import com.repoguard.agent.dto.RetryCompensationStatusDto;
import com.repoguard.agent.mapper.ReviewTaskMapper.MessageQueueHealthSummary;
import com.repoguard.agent.messaging.RabbitRuntimeHealthProbe;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class MessageQueueRuntimeConfigAssemblerTest {

    private final RabbitReviewQueueProperties properties = new RabbitReviewQueueProperties();
    private final RabbitRuntimeHealthProbe runtimeHealthProbe = org.mockito.Mockito.mock(RabbitRuntimeHealthProbe.class);
    private final MessageQueueRuntimeConfigAssembler assembler = new MessageQueueRuntimeConfigAssembler(
        properties,
        runtimeHealthProbe
    );

    @Test
    void assemblesActiveConfigWithRuntimeStatusAndConfigVersion() {
        when(runtimeHealthProbe.connectionStatus()).thenReturn("CONNECTED");

        ActiveRabbitMqConfigDto result = assembler.activeConfig(new RabbitMqIntegrationSettings(
            "RABBITMQ",
            "CONNECTED",
            "amqp://localhost:5672",
            "repoguard",
            "/",
            LocalDateTime.of(2026, 6, 10, 21, 2, 12),
            null,
            LocalDateTime.of(2026, 6, 10, 20, 58)
        ));

        assertThat(result.provider()).isEqualTo("RABBITMQ");
        assertThat(result.runtimeConnectionStatus()).isEqualTo("CONNECTED");
        assertThat(result.lastCheckedAt()).isEqualTo("2026-06-10 21:02:12");
        assertThat(result.updatedAt()).isEqualTo("2026-06-10 20:58:00");
        assertThat(result.configVersion()).isEqualTo("cfg-20260610-205800");
        assertThat(result.switchNotice()).contains("does not switch");
    }

    @Test
    void fallsBackToRuntimeDefaultVersionForMissingSettingsTimestamp() {
        when(runtimeHealthProbe.connectionStatus()).thenReturn("DISCONNECTED");

        ActiveRabbitMqConfigDto result = assembler.activeConfig(null);

        assertThat(result.runtimeConnectionStatus()).isEqualTo("DISCONNECTED");
        assertThat(result.configVersion()).isEqualTo("runtime-default");
        assertThat(result.updatedAt()).isNull();
    }

    @Test
    void assemblesTopologyFromQueueProperties() {
        properties.setExchange("review.exchange");
        properties.setQueue("review.queue");
        properties.setRoutingKey("review.routing");
        properties.setDeadLetterExchange("review.dlx");
        properties.setDeadLetterQueue("review.dlq");
        properties.setDeadLetterRoutingKey("review.dead");

        RabbitMqTopologyDto result = assembler.topology();

        assertThat(result.exchange()).isEqualTo("review.exchange");
        assertThat(result.queue()).isEqualTo("review.queue");
        assertThat(result.routingKey()).isEqualTo("review.routing");
        assertThat(result.deadLetterExchange()).isEqualTo("review.dlx");
        assertThat(result.deadLetterQueue()).isEqualTo("review.dlq");
        assertThat(result.deadLetterRoutingKey()).isEqualTo("review.dead");
    }

    @Test
    void clampsRetryCompensationSettingsAndCarriesFailureReason() {
        properties.setPublishCompensationMaxAttempts(0);
        properties.setPublishCompensationIntervalMs(10);
        properties.setPublishCompensationBatchSize(0);
        properties.setPublishCompensationLeaseMs(10);
        MessageQueueHealthSummary summary = new MessageQueueHealthSummary();
        summary.setClaimed(3L);

        RetryCompensationStatusDto result = assembler.retryCompensation(summary, "routing failed");

        assertThat(result.maxAttempts()).isEqualTo(1);
        assertThat(result.intervalMs()).isEqualTo(1000);
        assertThat(result.batchSize()).isEqualTo(1);
        assertThat(result.leaseMs()).isEqualTo(1000);
        assertThat(result.claimedTaskCount()).isEqualTo(3);
        assertThat(result.latestFailureReason()).isEqualTo("routing failed");
    }
}
