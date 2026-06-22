package com.repoguard.agent.service.impl;

import com.repoguard.agent.config.RabbitReviewQueueProperties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
class RabbitRuntimeHealthProbe {

    private final RabbitTemplate rabbitTemplate;
    private final RabbitReviewQueueProperties properties;

    RabbitRuntimeHealthProbe(RabbitTemplate rabbitTemplate, RabbitReviewQueueProperties properties) {
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
    }

    String connectionStatus() {
        CompletableFuture<Boolean> probe = CompletableFuture.supplyAsync(
            () -> rabbitTemplate.execute(channel -> channel.isOpen())
        );
        try {
            Boolean open = probe.get(runtimeConnectionTimeoutMs(), TimeUnit.MILLISECONDS);
            return Boolean.TRUE.equals(open) ? "CONNECTED" : "DISCONNECTED";
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return "DISCONNECTED";
        } catch (ExecutionException | RuntimeException | TimeoutException ex) {
            probe.cancel(true);
            return "DISCONNECTED";
        }
    }

    private long runtimeConnectionTimeoutMs() {
        return Math.max(100, properties.getHealthCheckTimeoutMs());
    }
}
