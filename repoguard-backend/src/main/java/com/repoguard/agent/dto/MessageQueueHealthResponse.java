package com.repoguard.agent.dto;

import java.util.List;

public record MessageQueueHealthResponse(
    ActiveRabbitMqConfigDto activeConfig,
    RabbitMqTopologyDto topology,
    List<MessageQueueMetricDto> metrics,
    RetryCompensationStatusDto retryCompensation,
    List<MessageQueueExceptionTaskDto> exceptionTasks,
    String generatedAt,
    String dataSource
) {
}
