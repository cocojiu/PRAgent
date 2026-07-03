package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.entity.IntegrationConfig;
import com.repoguard.agent.security.SecretCryptoService;
import org.junit.jupiter.api.Test;

class MysqlConnectionProbeTest {

    private final SecretCryptoService secretCryptoService = new SecretCryptoService("test-encryption-key");

    @Test
    void providerReturnsMysqlProviderCode() {
        MysqlConnectionProbe probe = new MysqlConnectionProbe(null, secretCryptoService);

        assertThat(probe.provider()).isEqualTo("MYSQL");
    }

    @Test
    void runtimeProbeReportsUnavailableWhenDataSourceIsMissing() {
        MysqlConnectionProbe probe = new MysqlConnectionProbe(null, secretCryptoService);

        ConnectionProbeResult result = probe.runtimeProbe();

        assertThat(result.healthy()).isNull();
        assertThat(result.status()).isEqualTo("unavailable");
        assertThat(result.message()).contains("Runtime DataSource");
    }

    @Test
    void configuredProbeUsesBoundedConnectionTimeoutProperties() {
        MysqlConnectionProbe probe = new MysqlConnectionProbe(null, secretCryptoService);
        IntegrationConfig config = new IntegrationConfig();
        config.setDefaultOwner(" root ");
        config.setTokenValue(secretCryptoService.encrypt(" root secret "));

        java.util.Properties properties = probe.connectionProperties(config);

        assertThat(properties.getProperty("user")).isEqualTo("root");
        assertThat(properties.getProperty("password")).isEqualTo(" root secret ");
        assertThat(properties.getProperty("connectTimeout")).isEqualTo("2000");
        assertThat(properties.getProperty("socketTimeout")).isEqualTo("2000");
    }

    @Test
    void configuredProbeReportsDriverErrorForInvalidJdbcUrl() {
        MysqlConnectionProbe probe = new MysqlConnectionProbe(null, secretCryptoService);
        IntegrationConfig config = new IntegrationConfig();
        config.setBaseUrl("jdbc:invalid://localhost/repoguard");
        config.setDefaultOwner("root");
        config.setTokenValue(secretCryptoService.encrypt("root"));

        ConnectionProbeResult result = probe.probe(config);

        assertThat(result.healthy()).isFalse();
        assertThat(result.status()).isEqualTo("failed");
        assertThat(result.message()).isNotBlank();
    }
}
