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
    private int publishMaxAttempts = 3;
    private long publishInitialIntervalMs = 500;
    private double publishMultiplier = 3.0;
    private long publishConfirmTimeoutMs = 5000;
    private int publishCompensationMaxAttempts = 5;
    private int publishCompensationBatchSize = 20;
    private long publishCompensationIntervalMs = 60000;
    private int workerConcurrency = 1;

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
    public int getPublishMaxAttempts() { return publishMaxAttempts; }
    public void setPublishMaxAttempts(int publishMaxAttempts) { this.publishMaxAttempts = publishMaxAttempts; }
    public long getPublishInitialIntervalMs() { return publishInitialIntervalMs; }
    public void setPublishInitialIntervalMs(long publishInitialIntervalMs) { this.publishInitialIntervalMs = publishInitialIntervalMs; }
    public double getPublishMultiplier() { return publishMultiplier; }
    public void setPublishMultiplier(double publishMultiplier) { this.publishMultiplier = publishMultiplier; }
    public long getPublishConfirmTimeoutMs() { return publishConfirmTimeoutMs; }
    public void setPublishConfirmTimeoutMs(long publishConfirmTimeoutMs) { this.publishConfirmTimeoutMs = publishConfirmTimeoutMs; }
    public int getPublishCompensationMaxAttempts() { return publishCompensationMaxAttempts; }
    public void setPublishCompensationMaxAttempts(int publishCompensationMaxAttempts) { this.publishCompensationMaxAttempts = publishCompensationMaxAttempts; }
    public int getPublishCompensationBatchSize() { return publishCompensationBatchSize; }
    public void setPublishCompensationBatchSize(int publishCompensationBatchSize) { this.publishCompensationBatchSize = publishCompensationBatchSize; }
    public long getPublishCompensationIntervalMs() { return publishCompensationIntervalMs; }
    public void setPublishCompensationIntervalMs(long publishCompensationIntervalMs) { this.publishCompensationIntervalMs = publishCompensationIntervalMs; }
    public int getWorkerConcurrency() { return workerConcurrency; }
    public void setWorkerConcurrency(int workerConcurrency) { this.workerConcurrency = workerConcurrency; }
}
