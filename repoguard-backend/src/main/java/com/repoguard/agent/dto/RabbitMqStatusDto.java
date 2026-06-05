package com.repoguard.agent.dto;

/**
 * 为后续执行链路预留的 RabbitMQ 投递状态区块。
 */
public record RabbitMqStatusDto(
    Integer deliveryCount,
    Integer retryCount,
    String consumeStatus
) {
}
