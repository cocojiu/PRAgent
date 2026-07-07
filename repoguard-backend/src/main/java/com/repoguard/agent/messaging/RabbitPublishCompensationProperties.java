package com.repoguard.agent.messaging;

public interface RabbitPublishCompensationProperties {

    int getPublishCompensationMaxAttempts();

    int getPublishCompensationBatchSize();

    long getPublishCompensationIntervalMs();

    long getPublishCompensationLeaseMs();
}
