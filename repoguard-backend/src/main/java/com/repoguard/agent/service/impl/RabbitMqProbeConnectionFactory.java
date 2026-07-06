package com.repoguard.agent.service.impl;

import com.repoguard.agent.entity.IntegrationConfig;
import com.repoguard.agent.security.SecretCryptoService;
import java.net.URI;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
class RabbitMqProbeConnectionFactory {

    static final int CONNECTION_TIMEOUT_MILLIS = 2_000;
    static final int REQUESTED_HEARTBEAT_SECONDS = 10;

    private final SecretCryptoService secretCryptoService;

    RabbitMqProbeConnectionFactory(SecretCryptoService secretCryptoService) {
        this.secretCryptoService = Objects.requireNonNull(secretCryptoService, "secretCryptoService");
    }

    com.rabbitmq.client.ConnectionFactory create(IntegrationConfig config) {
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
        factory.setConnectionTimeout(CONNECTION_TIMEOUT_MILLIS);
        factory.setRequestedHeartbeat(REQUESTED_HEARTBEAT_SECONDS);
        return factory;
    }

    private String pathVirtualHost(URI uri) {
        String path = uri.getPath();
        if (!StringUtils.hasText(path) || "/".equals(path)) {
            return "/";
        }
        return path.substring(1);
    }
}
