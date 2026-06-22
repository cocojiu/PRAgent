package com.repoguard.agent.service.impl;

import com.repoguard.agent.entity.IntegrationConfig;
import com.repoguard.agent.security.SecretCryptoService;
import java.sql.Connection;
import java.sql.DriverManager;
import javax.sql.DataSource;

/**
 * Probes runtime and saved MySQL connectivity.
 */
public class MysqlConnectionProbe implements ConnectionProbe<IntegrationConfig> {

    static final String PROVIDER = "MYSQL";

    private final DataSource dataSource;
    private final SecretCryptoService secretCryptoService;

    public MysqlConnectionProbe(DataSource dataSource, SecretCryptoService secretCryptoService) {
        this.dataSource = dataSource;
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
        if (dataSource == null) {
            return new ConnectionProbeResult(null, "unavailable", "Runtime DataSource is not available in this context");
        }
        try (Connection connection = dataSource.getConnection()) {
            boolean valid = connection.isValid(2);
            return new ConnectionProbeResult(
                valid,
                valid ? "connected" : "failed",
                valid ? "MySQL runtime connection is valid" : "MySQL runtime connection is not valid"
            );
        } catch (Exception ex) {
            return new ConnectionProbeResult(false, "failed", conciseError(ex));
        }
    }

    public ConnectionProbeResult configuredProbe(IntegrationConfig config) {
        try (Connection connection = DriverManager.getConnection(
            config.getBaseUrl(),
            config.getDefaultOwner(),
            secretCryptoService.decrypt(config.getTokenValue())
        )) {
            boolean valid = connection.isValid(2);
            return new ConnectionProbeResult(valid, valid ? "connected" : "failed", valid ? null : "MySQL connection is not valid");
        } catch (Exception ex) {
            return new ConnectionProbeResult(false, "failed", conciseError(ex));
        }
    }

    private String conciseError(Exception ex) {
        String message = ex.getMessage();
        if (!org.springframework.util.StringUtils.hasText(message) && ex.getCause() != null) {
            message = ex.getCause().getMessage();
        }
        if (!org.springframework.util.StringUtils.hasText(message)) {
            return ex.getClass().getSimpleName();
        }
        String normalized = message.replaceAll("\\s+", " ").trim();
        return normalized.length() > 240 ? normalized.substring(0, 237) + "..." : normalized;
    }
}
