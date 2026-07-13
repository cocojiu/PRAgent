package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.entity.IntegrationConfig;
import com.repoguard.agent.security.SecretCryptoService;
import org.junit.jupiter.api.Test;

class RabbitMqProbeConnectionFactoryTest {

    private final SecretCryptoService secretCryptoService = new SecretCryptoService("test-encryption-key");
    private final RabbitMqProbeConnectionFactory factory = new RabbitMqProbeConnectionFactory(secretCryptoService);

    @Test
    void createUsesExplicitResourceAsVirtualHost() throws Exception {
        IntegrationConfig config = rabbitConfig("amqp://mq.example.com:5673/from-url");
        config.setDefaultOwner("repo_user");
        config.setDefaultRepo("configured-vhost");

        com.rabbitmq.client.ConnectionFactory connectionFactory = factory.create(config);

        assertThat(connectionFactory.getHost()).isEqualTo("mq.example.com");
        assertThat(connectionFactory.getPort()).isEqualTo(5673);
        assertThat(connectionFactory.getUsername()).isEqualTo("repo_user");
        assertThat(connectionFactory.getPassword()).isEqualTo("secret");
        assertThat(connectionFactory.getVirtualHost()).isEqualTo("configured-vhost");
        assertThat(connectionFactory.getConnectionTimeout())
            .isEqualTo(RabbitMqProbeConnectionFactory.CONNECTION_TIMEOUT_MILLIS);
        assertThat(connectionFactory.getRequestedHeartbeat())
            .isEqualTo(RabbitMqProbeConnectionFactory.REQUESTED_HEARTBEAT_SECONDS);
    }

    @Test
    void createFallsBackToUrlPathVirtualHost() throws Exception {
        IntegrationConfig config = rabbitConfig("amqp://mq.example.com:5672/path-vhost");

        com.rabbitmq.client.ConnectionFactory connectionFactory = factory.create(config);

        assertThat(connectionFactory.getVirtualHost()).isEqualTo("path-vhost");
    }

    @Test
    void createFallsBackToRootVirtualHostWhenUrlPathIsMissing() throws Exception {
        IntegrationConfig config = rabbitConfig("amqp://mq.example.com:5672/");

        com.rabbitmq.client.ConnectionFactory connectionFactory = factory.create(config);

        assertThat(connectionFactory.getVirtualHost()).isEqualTo("/");
    }

    @Test
    void createEnablesTlsAndSecureDefaultPortForAmqps() {
        IntegrationConfig config = rabbitConfig("amqps://mq.example.com/secure-vhost");

        com.rabbitmq.client.ConnectionFactory connectionFactory = factory.create(config);

        assertThat(connectionFactory.isSSL()).isTrue();
        assertThat(connectionFactory.getPort()).isEqualTo(5671);
        assertThat(connectionFactory.getHandshakeTimeout())
            .isEqualTo(RabbitMqProbeConnectionFactory.HANDSHAKE_TIMEOUT_MILLIS);
    }

    private IntegrationConfig rabbitConfig(String baseUrl) {
        IntegrationConfig config = new IntegrationConfig();
        config.setBaseUrl(baseUrl);
        config.setTokenValue(secretCryptoService.encrypt("secret"));
        return config;
    }
}
