package com.repoguard.agent.service.impl;

import com.repoguard.agent.entity.IntegrationConfig;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Probes runtime and saved RabbitMQ connectivity.
 */
@Component
public class RabbitMqConnectionProbe implements ConnectionProbe<IntegrationConfig> {

    static final String PROVIDER = "RABBITMQ";

    private final RabbitTemplate rabbitTemplate;
    private final RabbitMqProbeConnectionFactory connectionFactory;
    private final RabbitRuntimeHealthProbe runtimeHealthProbe;

    @Autowired
    public RabbitMqConnectionProbe(
        RabbitTemplate rabbitTemplate,
        RabbitMqProbeConnectionFactory connectionFactory,
        RabbitRuntimeHealthProbe runtimeHealthProbe
    ) {
        this.rabbitTemplate = rabbitTemplate;
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory");
        this.runtimeHealthProbe = Objects.requireNonNull(runtimeHealthProbe, "runtimeHealthProbe");
    }

    public RabbitMqConnectionProbe(RabbitTemplate rabbitTemplate, RabbitMqProbeConnectionFactory connectionFactory) {
        this.rabbitTemplate = rabbitTemplate;
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory");
        this.runtimeHealthProbe = null;
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public ConnectionProbeResult probe(IntegrationConfig config) {
        return configuredProbe(config);
    }

    public ConnectionProbeResult runtimeProbe() {
        if (runtimeHealthProbe != null) {
            boolean connected = "CONNECTED".equals(runtimeHealthProbe.connectionStatus());
            return new ConnectionProbeResult(
                connected,
                connected ? "connected" : "failed",
                connected ? "RabbitMQ runtime channel is open" : "RabbitMQ channel is not open"
            );
        }
        if (rabbitTemplate == null) {
            return new ConnectionProbeResult(null, "unavailable", "Runtime RabbitTemplate is not available in this context");
        }
        try {
            Boolean open = rabbitTemplate.execute(channel -> channel.isOpen());
            boolean connected = Boolean.TRUE.equals(open);
            return new ConnectionProbeResult(
                connected,
                connected ? "connected" : "failed",
                connected ? "RabbitMQ runtime channel is open" : "RabbitMQ channel is not open"
            );
        } catch (RuntimeException ex) {
            return new ConnectionProbeResult(false, "failed", conciseError(ex));
        }
    }

    public ConnectionProbeResult configuredProbe(IntegrationConfig config) {
        try (com.rabbitmq.client.Connection connection = connectionFactory.create(config).newConnection()) {
            boolean open = connection.isOpen();
            return new ConnectionProbeResult(open, open ? "connected" : "failed", open ? null : "RabbitMQ connection is not open");
        } catch (Exception ex) {
            return new ConnectionProbeResult(false, "failed", conciseError(ex));
        }
    }

    private String conciseError(Exception ex) {
        return ConnectionProbeErrorMessage.concise(ex);
    }
}
