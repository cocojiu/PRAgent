package com.repoguard.agent.integration.connection;

import com.repoguard.agent.entity.IntegrationConfig;
import com.repoguard.agent.external.OutboundEndpointPolicy;
import com.repoguard.agent.external.OutboundEndpointType;
import com.repoguard.agent.security.SecretCryptoService;
import java.net.URI;
import java.security.GeneralSecurityException;
import javax.net.ssl.SSLContext;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;

@Component
class RabbitMqProbeConnectionFactory {

    static final int CONNECTION_TIMEOUT_MILLIS = 2_000;
    static final int HANDSHAKE_TIMEOUT_MILLIS = 2_000;
    static final int REQUESTED_HEARTBEAT_SECONDS = 10;

    private final SecretCryptoService secretCryptoService;
    private final OutboundEndpointPolicy endpointPolicy;

    RabbitMqProbeConnectionFactory(SecretCryptoService secretCryptoService) {
        this(secretCryptoService, null);
    }

    @Autowired
    RabbitMqProbeConnectionFactory(
        SecretCryptoService secretCryptoService,
        OutboundEndpointPolicy endpointPolicy
    ) {
        this.secretCryptoService = Objects.requireNonNull(secretCryptoService, "secretCryptoService");
        this.endpointPolicy = endpointPolicy;
    }

    com.rabbitmq.client.ConnectionFactory create(IntegrationConfig config) {
        if (endpointPolicy != null) {
            endpointPolicy.validate(OutboundEndpointType.RABBITMQ, config.getBaseUrl());
        }
        URI uri = URI.create(config.getBaseUrl());
        com.rabbitmq.client.ConnectionFactory factory = new com.rabbitmq.client.ConnectionFactory();
        if (StringUtils.hasText(uri.getHost())) {
            factory.setHost(uri.getHost());
        }
        boolean tls = "amqps".equalsIgnoreCase(uri.getScheme());
        factory.setPort(uri.getPort() > 0 ? uri.getPort() : (tls ? 5671 : 5672));
        if (tls) {
            enableTls(factory);
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
        factory.setHandshakeTimeout(HANDSHAKE_TIMEOUT_MILLIS);
        factory.setRequestedHeartbeat(REQUESTED_HEARTBEAT_SECONDS);
        return factory;
    }

    private void enableTls(com.rabbitmq.client.ConnectionFactory factory) {
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, null, null);
            factory.useSslProtocol(sslContext);
            factory.enableHostnameVerification();
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("RabbitMQ TLS is unavailable", ex);
        }
    }

    private String pathVirtualHost(URI uri) {
        String path = uri.getPath();
        if (!StringUtils.hasText(path) || "/".equals(path)) {
            return "/";
        }
        return path.substring(1);
    }
}
