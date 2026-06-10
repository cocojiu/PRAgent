package com.repoguard.agent.dto;

public record RabbitMqTopologyDto(
    String exchange,
    String queue,
    String routingKey,
    String deadLetterExchange,
    String deadLetterQueue,
    String deadLetterRoutingKey
) {
}
