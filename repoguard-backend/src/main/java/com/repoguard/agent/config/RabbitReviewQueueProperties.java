package com.repoguard.agent.config;

import com.repoguard.agent.messaging.RabbitPublishProperties;
import com.repoguard.agent.messaging.RabbitPublishCompensationProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.rabbit.review")
public class RabbitReviewQueueProperties implements RabbitPublishProperties, RabbitPublishCompensationProperties {

    private String exchange = "repoguard.review.exchange.v3";
    private String queue = "repoguard.review.queue.v3";
    private String routingKey = "repoguard.review.created.v3";
    private String deadLetterExchange = "repoguard.review.dlx";
    private String deadLetterQueue = "repoguard.review.dlq";
    private String deadLetterRoutingKey = "repoguard.review.dead";
    private long publishConfirmTimeoutMs = 5000;
    private int publishCompensationMaxAttempts = 10;
    private long publishCompensationIntervalMs = 60000;
    private int publishCompensationBatchSize = 20;
    private long publishCompensationLeaseMs = 120000;
    private long reviewRecoveryIntervalMs = 60000;
    private long reviewExecutionTimeoutMs = 1800000;
    private int reviewRecoveryBatchSize = 20;
    private int workerConcurrency = 1;
    private long healthCheckTimeoutMs = 1500;
    private int healthQueryWindowDays = 30;

    public String getExchange() {
        return exchange;
    }

    public void setExchange(String exchange) {
        this.exchange = exchange;
    }

    public String getQueue() {
        return queue;
    }

    public void setQueue(String queue) {
        this.queue = queue;
    }

    public String getRoutingKey() {
        return routingKey;
    }

    public void setRoutingKey(String routingKey) {
        this.routingKey = routingKey;
    }

    public String getDeadLetterExchange() {
        return deadLetterExchange;
    }

    public void setDeadLetterExchange(String deadLetterExchange) {
        this.deadLetterExchange = deadLetterExchange;
    }

    public String getDeadLetterQueue() {
        return deadLetterQueue;
    }

    public void setDeadLetterQueue(String deadLetterQueue) {
        this.deadLetterQueue = deadLetterQueue;
    }

    public String getDeadLetterRoutingKey() {
        return deadLetterRoutingKey;
    }

    public void setDeadLetterRoutingKey(String deadLetterRoutingKey) {
        this.deadLetterRoutingKey = deadLetterRoutingKey;
    }

    public long getPublishConfirmTimeoutMs() {
        return publishConfirmTimeoutMs;
    }

    public void setPublishConfirmTimeoutMs(long publishConfirmTimeoutMs) {
        this.publishConfirmTimeoutMs = publishConfirmTimeoutMs;
    }

    public int getPublishCompensationMaxAttempts() {
        return publishCompensationMaxAttempts;
    }

    public void setPublishCompensationMaxAttempts(int publishCompensationMaxAttempts) {
        this.publishCompensationMaxAttempts = publishCompensationMaxAttempts;
    }

    public long getPublishCompensationIntervalMs() {
        return publishCompensationIntervalMs;
    }

    public void setPublishCompensationIntervalMs(long publishCompensationIntervalMs) {
        this.publishCompensationIntervalMs = publishCompensationIntervalMs;
    }

    public int getPublishCompensationBatchSize() {
        return publishCompensationBatchSize;
    }

    public void setPublishCompensationBatchSize(int publishCompensationBatchSize) {
        this.publishCompensationBatchSize = publishCompensationBatchSize;
    }

    public long getPublishCompensationLeaseMs() {
        return publishCompensationLeaseMs;
    }

    public void setPublishCompensationLeaseMs(long publishCompensationLeaseMs) {
        this.publishCompensationLeaseMs = publishCompensationLeaseMs;
    }

    public long getReviewRecoveryIntervalMs() {
        return reviewRecoveryIntervalMs;
    }

    public void setReviewRecoveryIntervalMs(long reviewRecoveryIntervalMs) {
        this.reviewRecoveryIntervalMs = reviewRecoveryIntervalMs;
    }

    public long getReviewExecutionTimeoutMs() {
        return reviewExecutionTimeoutMs;
    }

    public void setReviewExecutionTimeoutMs(long reviewExecutionTimeoutMs) {
        this.reviewExecutionTimeoutMs = reviewExecutionTimeoutMs;
    }

    public int getReviewRecoveryBatchSize() {
        return reviewRecoveryBatchSize;
    }

    public void setReviewRecoveryBatchSize(int reviewRecoveryBatchSize) {
        this.reviewRecoveryBatchSize = reviewRecoveryBatchSize;
    }

    public int getWorkerConcurrency() {
        return workerConcurrency;
    }

    public void setWorkerConcurrency(int workerConcurrency) {
        this.workerConcurrency = workerConcurrency;
    }

    public long getHealthCheckTimeoutMs() {
        return healthCheckTimeoutMs;
    }

    public void setHealthCheckTimeoutMs(long healthCheckTimeoutMs) {
        this.healthCheckTimeoutMs = healthCheckTimeoutMs;
    }

    public int getHealthQueryWindowDays() {
        return healthQueryWindowDays;
    }

    public void setHealthQueryWindowDays(int healthQueryWindowDays) {
        this.healthQueryWindowDays = healthQueryWindowDays;
    }
}
