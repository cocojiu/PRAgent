package com.repoguard.agent.messaging;

public interface RabbitPublishProperties {

    String getExchange();

    String getRoutingKey();

    int getPublishMaxAttempts();

    long getPublishInitialIntervalMs();

    double getPublishMultiplier();

    long getPublishConfirmTimeoutMs();
}
