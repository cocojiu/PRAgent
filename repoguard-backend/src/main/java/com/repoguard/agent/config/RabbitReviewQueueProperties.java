package com.repoguard.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.rabbit.review")
public class RabbitReviewQueueProperties {

    private String exchange = "repoguard.review.exchange.v2";
    private String queue = "repoguard.review.queue.v2";
    private String routingKey = "repoguard.review.created.v2";
    private String deadLetterExchange = "repoguard.review.dlx";
    private String deadLetterQueue = "repoguard.review.dlq";
    private String deadLetterRoutingKey = "repoguard.review.dead";
    private int publishMaxAttempts = 3;
    private long publishInitialIntervalMs = 500;
    private double publishMultiplier = 3.0;
    private long publishConfirmTimeoutMs = 5000;
    private int publishCompensationMaxAttempts = 10;
    private long publishCompensationIntervalMs = 60000;
    private int publishCompensationBatchSize = 20;
    private long publishCompensationLeaseMs = 120000;
    private int workerConcurrency = 1;

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

    public int getPublishMaxAttempts() {
        return publishMaxAttempts;
    }

    public void setPublishMaxAttempts(int publishMaxAttempts) {
        this.publishMaxAttempts = publishMaxAttempts;
    }

    public long getPublishInitialIntervalMs() {
        return publishInitialIntervalMs;
    }

    public void setPublishInitialIntervalMs(long publishInitialIntervalMs) {
        this.publishInitialIntervalMs = publishInitialIntervalMs;
    }

    public double getPublishMultiplier() {
        return publishMultiplier;
    }

    public void setPublishMultiplier(double publishMultiplier) {
        this.publishMultiplier = publishMultiplier;
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

    public int getWorkerConcurrency() {
        return workerConcurrency;
    }

    public void setWorkerConcurrency(int workerConcurrency) {
        this.workerConcurrency = workerConcurrency;
    }
}
