package com.repoguard.agent.service.impl;

import com.repoguard.agent.entity.IntegrationConfig;
import com.repoguard.agent.security.SecretCryptoService;
import java.net.URI;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.util.StringUtils;

/**
 * Probes runtime and saved RabbitMQ connectivity.
 */
public class RabbitMqConnectionProbe implements ConnectionProbe<IntegrationConfig> {

    static final String PROVIDER = "RABBITMQ";

    private final RabbitTemplate rabbitTemplate;
    private final SecretCryptoService secretCryptoService;

    public RabbitMqConnectionProbe(RabbitTemplate rabbitTemplate, SecretCryptoService secretCryptoService) {
        this.rabbitTemplate = rabbitTemplate;
        this.secretCryptoService = secretCryptoService;
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
        try (com.rabbitmq.client.Connection connection = rabbitMqConnectionFactory(config).newConnection()) {
            boolean open = connection.isOpen();
            return new ConnectionProbeResult(open, open ? "connected" : "failed", open ? null : "RabbitMQ connection is not open");
        } catch (Exception ex) {
            return new ConnectionProbeResult(false, "failed", conciseError(ex));
        }
    }

    com.rabbitmq.client.ConnectionFactory rabbitMqConnectionFactory(IntegrationConfig config) {
        URI uri = URI.create(config.getBaseUrl());
        com.rabbitmq.client.ConnectionFactory factory = new com.rabbitmq.client.ConnectionFactory();
        if (StringUtils.hasText(uri.getHost())) {
            factory.setHost(uri.getHost());
        }
        if (uri.getPort() > 0) {
            factory.setPort(uri.getPort());
        }
        if (StringUtils.hasText(config.getDefaultOwner())) {
            factory.setUsername(config.getDefaultOwner());
        }
        String secret = secretCryptoService.decrypt(config.getTokenValue());
        if (StringUtils.hasText(secret)) {
            factory.setPassword(secret);
        }
        String virtualHost = StringUtils.hasText(config.getDefaultRepo()) ? config.getDefaultRepo() : pathVirtualHost(uri);
        if (StringUtils.hasText(virtualHost)) {
            factory.setVirtualHost(virtualHost);
        }
        factory.setConnectionTimeout(2_000);
        factory.setRequestedHeartbeat(10);
        return factory;
    }

    private String pathVirtualHost(URI uri) {
        String path = uri.getPath();
        if (!StringUtils.hasText(path) || "/".equals(path)) {
            return "/";
        }
        return path.substring(1);
    }

    private String conciseError(Exception ex) {
        String message = ex.getMessage();
        if (!StringUtils.hasText(message) && ex.getCause() != null) {
            message = ex.getCause().getMessage();
        }
        if (!StringUtils.hasText(message)) {
            return ex.getClass().getSimpleName();
        }
        String normalized = message.replaceAll("\\s+", " ").trim();
        return normalized.length() > 240 ? normalized.substring(0, 237) + "..." : normalized;
    }
}
