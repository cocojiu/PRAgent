package com.repoguard.agent.config;

import com.repoguard.agent.messaging.RabbitPublishProperties;
import com.repoguard.agent.messaging.RabbitPublishCompensationProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.rabbit.notification")
public class RabbitNotificationQueueProperties implements RabbitPublishProperties, RabbitPublishCompensationProperties {

    private String exchange = "repoguard.notification.exchange";
    private String queue = "repoguard.notification.queue";
    private String routingKey = "repoguard.notification.created";
    private String deadLetterExchange = "repoguard.notification.dlx";
    private String deadLetterQueue = "repoguard.notification.dlq";
    private String deadLetterRoutingKey = "repoguard.notification.dead";
    private long publishConfirmTimeoutMs = 5000;
    private int publishCompensationMaxAttempts = 5;
    private int publishCompensationBatchSize = 20;
    private long publishCompensationIntervalMs = 60000;
    private long publishCompensationLeaseMs = 120000;
    private int workerConcurrency = 1;
    private long deliveryClaimLeaseMs = 300000;
    private long deliveryRecoveryIntervalMs = 60000;
    private int deliveryRecoveryBatchSize = 20;

    public String getExchange() { return exchange; }
    public void setExchange(String exchange) { this.exchange = exchange; }
    public String getQueue() { return queue; }
    public void setQueue(String queue) { this.queue = queue; }
    public String getRoutingKey() { return routingKey; }
    public void setRoutingKey(String routingKey) { this.routingKey = routingKey; }
    public String getDeadLetterExchange() { return deadLetterExchange; }
    public void setDeadLetterExchange(String deadLetterExchange) { this.deadLetterExchange = deadLetterExchange; }
    public String getDeadLetterQueue() { return deadLetterQueue; }
    public void setDeadLetterQueue(String deadLetterQueue) { this.deadLetterQueue = deadLetterQueue; }
    public String getDeadLetterRoutingKey() { return deadLetterRoutingKey; }
    public void setDeadLetterRoutingKey(String deadLetterRoutingKey) { this.deadLetterRoutingKey = deadLetterRoutingKey; }
    public long getPublishConfirmTimeoutMs() { return publishConfirmTimeoutMs; }
    public void setPublishConfirmTimeoutMs(long publishConfirmTimeoutMs) { this.publishConfirmTimeoutMs = publishConfirmTimeoutMs; }
    public int getPublishCompensationMaxAttempts() { return publishCompensationMaxAttempts; }
    public void setPublishCompensationMaxAttempts(int publishCompensationMaxAttempts) { this.publishCompensationMaxAttempts = publishCompensationMaxAttempts; }
    public int getPublishCompensationBatchSize() { return publishCompensationBatchSize; }
    public void setPublishCompensationBatchSize(int publishCompensationBatchSize) { this.publishCompensationBatchSize = publishCompensationBatchSize; }
    public long getPublishCompensationIntervalMs() { return publishCompensationIntervalMs; }
    public void setPublishCompensationIntervalMs(long publishCompensationIntervalMs) { this.publishCompensationIntervalMs = publishCompensationIntervalMs; }
    public long getPublishCompensationLeaseMs() { return publishCompensationLeaseMs; }
    public void setPublishCompensationLeaseMs(long publishCompensationLeaseMs) { this.publishCompensationLeaseMs = publishCompensationLeaseMs; }
    public int getWorkerConcurrency() { return workerConcurrency; }
    public void setWorkerConcurrency(int workerConcurrency) { this.workerConcurrency = workerConcurrency; }
    public long getDeliveryClaimLeaseMs() { return deliveryClaimLeaseMs; }
    public void setDeliveryClaimLeaseMs(long deliveryClaimLeaseMs) { this.deliveryClaimLeaseMs = deliveryClaimLeaseMs; }
    public long getDeliveryRecoveryIntervalMs() { return deliveryRecoveryIntervalMs; }
    public void setDeliveryRecoveryIntervalMs(long deliveryRecoveryIntervalMs) { this.deliveryRecoveryIntervalMs = deliveryRecoveryIntervalMs; }
    public int getDeliveryRecoveryBatchSize() { return deliveryRecoveryBatchSize; }
    public void setDeliveryRecoveryBatchSize(int deliveryRecoveryBatchSize) { this.deliveryRecoveryBatchSize = deliveryRecoveryBatchSize; }
}
