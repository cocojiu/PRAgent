package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.entity.IntegrationConfig;
import com.repoguard.agent.security.SecretCryptoService;
import org.junit.jupiter.api.Test;

class RabbitMqConnectionProbeTest {

    private final SecretCryptoService secretCryptoService = new SecretCryptoService("test-encryption-key");
    private final RabbitMqConnectionProbe probe = new RabbitMqConnectionProbe(null, secretCryptoService);

    @Test
    void providerReturnsRabbitMqProviderCode() {
        assertThat(probe.provider()).isEqualTo("RABBITMQ");
    }

    @Test
    void runtimeProbeReportsUnavailableWhenRabbitTemplateIsMissing() {
        ConnectionProbeResult result = probe.runtimeProbe();

        assertThat(result.healthy()).isNull();
        assertThat(result.status()).isEqualTo("unavailable");
        assertThat(result.message()).contains("Runtime RabbitTemplate");
    }

    @Test
    void rabbitMqConnectionFactoryUsesExplicitResourceAsVirtualHost() throws Exception {
        IntegrationConfig config = rabbitConfig("amqp://mq.example.com:5673/from-url");
        config.setDefaultOwner("repo_user");
        config.setDefaultRepo("configured-vhost");

        com.rabbitmq.client.ConnectionFactory factory = probe.rabbitMqConnectionFactory(config);

        assertThat(factory.getHost()).isEqualTo("mq.example.com");
        assertThat(factory.getPort()).isEqualTo(5673);
        assertThat(factory.getUsername()).isEqualTo("repo_user");
        assertThat(factory.getPassword()).isEqualTo("secret");
        assertThat(factory.getVirtualHost()).isEqualTo("configured-vhost");
    }

    @Test
    void rabbitMqConnectionFactoryFallsBackToUrlPathVirtualHost() throws Exception {
        IntegrationConfig config = rabbitConfig("amqp://mq.example.com:5672/path-vhost");

        com.rabbitmq.client.ConnectionFactory factory = probe.rabbitMqConnectionFactory(config);

        assertThat(factory.getVirtualHost()).isEqualTo("path-vhost");
    }

    private IntegrationConfig rabbitConfig(String baseUrl) {
        IntegrationConfig config = new IntegrationConfig();
        config.setBaseUrl(baseUrl);
        config.setTokenValue(secretCryptoService.encrypt("secret"));
        return config;
    }
}
