package com.repoguard.agent.service.impl;

import com.repoguard.agent.config.RabbitReviewQueueProperties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import com.repoguard.agent.concurrency.AsyncExecutorProperties;

@Component
public class RabbitRuntimeHealthProbe {

    private final RabbitTemplate rabbitTemplate;
    private final RabbitReviewQueueProperties properties;
    private final Executor executor;
    private final AsyncExecutorProperties asyncProperties;
    private volatile String cachedStatus;
    private volatile long cachedUntilMillis;
    private volatile CompletableFuture<Boolean> inFlightProbe;

    public RabbitRuntimeHealthProbe(RabbitTemplate rabbitTemplate, RabbitReviewQueueProperties properties) {
        this(rabbitTemplate, properties, Runnable::run, new AsyncExecutorProperties());
    }

    @Autowired
    RabbitRuntimeHealthProbe(
        RabbitTemplate rabbitTemplate,
        RabbitReviewQueueProperties properties,
        @Qualifier(RabbitHealthProbeExecutorConfig.RABBIT_HEALTH_EXECUTOR) Executor executor,
        AsyncExecutorProperties asyncProperties
    ) {
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
        this.executor = executor;
        this.asyncProperties = asyncProperties;
    }

    public String connectionStatus() {
        long now = System.currentTimeMillis();
        if (cachedStatus != null && now < cachedUntilMillis) {
            return cachedStatus;
        }
        try {
            CompletableFuture<Boolean> probe = currentProbe();
            Boolean open = probe.get(runtimeConnectionTimeoutMs(), TimeUnit.MILLISECONDS);
            return cache(Boolean.TRUE.equals(open) ? "CONNECTED" : "DISCONNECTED", false);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return cache("DISCONNECTED", true);
        } catch (ExecutionException | RuntimeException | TimeoutException ex) {
            CompletableFuture<Boolean> probe = inFlightProbe;
            if (probe != null) {
                probe.cancel(true);
            }
            return cache("DISCONNECTED", true);
        }
    }

    private synchronized CompletableFuture<Boolean> currentProbe() {
        if (inFlightProbe != null && !inFlightProbe.isDone()) {
            return inFlightProbe;
        }
        CompletableFuture<Boolean> probe = CompletableFuture.supplyAsync(
            () -> rabbitTemplate.execute(channel -> channel.isOpen()),
            executor
        );
        inFlightProbe = probe;
        probe.whenComplete((ignored, failure) -> clearCompletedProbe(probe));
        return probe;
    }

    private synchronized void clearCompletedProbe(CompletableFuture<Boolean> completed) {
        if (inFlightProbe == completed) {
            inFlightProbe = null;
        }
    }

    private String cache(String status, boolean failure) {
        cachedStatus = status;
        long duration = failure
            ? asyncProperties.getRabbitHealthFailureBackoffMillis()
            : asyncProperties.getRabbitHealthCacheMillis();
        cachedUntilMillis = System.currentTimeMillis() + Math.max(100, duration);
        return status;
    }

    private long runtimeConnectionTimeoutMs() {
        return Math.max(100, properties.getHealthCheckTimeoutMs());
    }
}
