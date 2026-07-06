package com.repoguard.agent.service.impl;

import com.repoguard.agent.config.RabbitMqIntegrationSettings;
import com.repoguard.agent.config.RabbitReviewQueueProperties;
import com.repoguard.agent.dto.ActiveRabbitMqConfigDto;
import com.repoguard.agent.dto.RabbitMqTopologyDto;
import com.repoguard.agent.dto.RetryCompensationStatusDto;
import com.repoguard.agent.mapper.ReviewTaskMapper.MessageQueueHealthSummary;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

class MessageQueueRuntimeConfigAssembler {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter VERSION_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final RabbitReviewQueueProperties properties;
    private final RabbitRuntimeHealthProbe runtimeHealthProbe;

    MessageQueueRuntimeConfigAssembler(
        RabbitReviewQueueProperties properties,
        RabbitRuntimeHealthProbe runtimeHealthProbe
    ) {
        this.properties = properties;
        this.runtimeHealthProbe = runtimeHealthProbe;
    }

    ActiveRabbitMqConfigDto activeConfig(RabbitMqIntegrationSettings settings) {
        RabbitMqIntegrationSettings normalized = settings == null
            ? RabbitMqIntegrationSettings.empty()
            : settings;
        return new ActiveRabbitMqConfigDto(
            normalized.provider(),
            normalized.status(),
            runtimeConnectionStatus(),
            normalized.baseUrl(),
            normalized.username(),
            normalized.virtualHost(),
            format(normalized.lastCheckedAt()),
            normalized.lastError(),
            format(normalized.updatedAt()),
            configVersion(normalized),
            "Testing a connection does not switch the active configuration; save integration settings to take effect."
        );
    }

    RabbitMqTopologyDto topology() {
        return new RabbitMqTopologyDto(
            properties.getExchange(),
            properties.getQueue(),
            properties.getRoutingKey(),
            properties.getDeadLetterExchange(),
            properties.getDeadLetterQueue(),
            properties.getDeadLetterRoutingKey()
        );
    }

    RetryCompensationStatusDto retryCompensation(MessageQueueHealthSummary summary, String latestFailureReason) {
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

    int maxAttempts() {
        return Math.max(1, properties.getPublishCompensationMaxAttempts());
    }

    private String runtimeConnectionStatus() {
        return runtimeHealthProbe == null ? "UNKNOWN" : runtimeHealthProbe.connectionStatus();
    }

    private String configVersion(RabbitMqIntegrationSettings settings) {
        if (settings.updatedAt() == null) {
            return "runtime-default";
        }
        return "cfg-" + settings.updatedAt().format(VERSION_FORMATTER);
    }

    private long safeCount(Long value) {
        return value == null ? 0L : value;
    }

    private String format(LocalDateTime value) {
        return value == null ? null : value.format(DATE_TIME_FORMATTER);
    }
}
