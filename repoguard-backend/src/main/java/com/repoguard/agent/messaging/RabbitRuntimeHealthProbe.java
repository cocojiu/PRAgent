package com.repoguard.agent.messaging;

import com.repoguard.agent.concurrency.AsyncExecutorProperties;
import com.repoguard.agent.config.RabbitReviewQueueProperties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class RabbitRuntimeHealthProbe {

    private final RabbitTemplate rabbitTemplate;
    private final RabbitReviewQueueProperties properties;
    private final Executor executor;
    private final AsyncExecutorProperties asyncProperties;
    private record CachedStatus(String value, long untilMillis) { }
    private volatile CachedStatus cached;
    // Private and never cancelled by waiters: isDone tracks the supplier's actual completion.
    private CompletableFuture<Boolean> inFlightProbe;

    public RabbitRuntimeHealthProbe(RabbitTemplate rabbitTemplate, RabbitReviewQueueProperties properties) {
        this(rabbitTemplate, properties, Runnable::run, new AsyncExecutorProperties());
    }

    public static RabbitRuntimeHealthProbe forTesting(
        RabbitTemplate rabbitTemplate,
        RabbitReviewQueueProperties properties,
        Executor executor,
        AsyncExecutorProperties asyncProperties
    ) {
        return new RabbitRuntimeHealthProbe(rabbitTemplate, properties, executor, asyncProperties);
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
        CachedStatus snapshot = cached;
        if (snapshot != null && now < snapshot.untilMillis()) {
            return snapshot.value();
        }
        try {
            CompletableFuture<Boolean> probe = currentProbe();
            Boolean open = probe.get(runtimeConnectionTimeoutMs(), TimeUnit.MILLISECONDS);
            return cache(Boolean.TRUE.equals(open) ? "CONNECTED" : "DISCONNECTED", false);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return "UNKNOWN";
        } catch (ExecutionException | RuntimeException | TimeoutException ex) {
            return cache("UNKNOWN", true);
        }
    }

    private synchronized CompletableFuture<Boolean> currentProbe() {
        if (inFlightProbe == null || inFlightProbe.isDone()) {
            inFlightProbe = CompletableFuture.supplyAsync(
                () -> rabbitTemplate.execute(channel -> channel.isOpen()), executor);
        }
        return inFlightProbe;
    }

    private String cache(String status, boolean failure) {
        long duration = failure
            ? asyncProperties.getRabbitHealthFailureBackoffMillis()
            : asyncProperties.getRabbitHealthCacheMillis();
        cached = new CachedStatus(status, System.currentTimeMillis() + Math.max(100, duration));
        return status;
    }

    private long runtimeConnectionTimeoutMs() {
        return Math.max(100, properties.getHealthCheckTimeoutMs());
    }
}
