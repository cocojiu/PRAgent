package com.repoguard.agent.messaging;

public interface RabbitPublishProperties {

    String getExchange();

    String getRoutingKey();

    long getPublishConfirmTimeoutMs();
}
