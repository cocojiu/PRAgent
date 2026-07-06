package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.repoguard.agent.security.SecretCryptoService;
import org.junit.jupiter.api.Test;

class RabbitMqConnectionProbeTest {

    private final SecretCryptoService secretCryptoService = new SecretCryptoService("test-encryption-key");
    private final RabbitMqProbeConnectionFactory connectionFactory =
        new RabbitMqProbeConnectionFactory(secretCryptoService);
    private final RabbitMqConnectionProbe probe = new RabbitMqConnectionProbe(null, connectionFactory);

    @Test
    void providerReturnsRabbitMqProviderCode() {
        assertThat(probe.provider()).isEqualTo("RABBITMQ");
    }

    @Test
    void constructorRejectsMissingConnectionFactory() {
        assertThatThrownBy(() -> new RabbitMqConnectionProbe(null, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("connectionFactory");
    }

    @Test
    void runtimeProbeReportsUnavailableWhenRabbitTemplateIsMissing() {
        ConnectionProbeResult result = probe.runtimeProbe();

        assertThat(result.healthy()).isNull();
        assertThat(result.status()).isEqualTo("unavailable");
        assertThat(result.message()).contains("Runtime RabbitTemplate");
    }
}
