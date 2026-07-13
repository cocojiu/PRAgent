package com.repoguard.agent.service.impl;

import com.repoguard.agent.entity.IntegrationConfig;
import com.repoguard.agent.external.OutboundEndpointPolicy;
import com.repoguard.agent.external.OutboundEndpointType;
import com.repoguard.agent.security.SecretCryptoService;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Properties;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Probes runtime and saved MySQL connectivity.
 */
@Component
public class MysqlConnectionProbe implements ConnectionProbe<IntegrationConfig> {

    static final String PROVIDER = "MYSQL";
    static final String CONNECT_TIMEOUT_MILLIS = "2000";
    static final String SOCKET_TIMEOUT_MILLIS = "2000";

    private final DataSource dataSource;
    private final SecretCryptoService secretCryptoService;
    private final OutboundEndpointPolicy endpointPolicy;

    public MysqlConnectionProbe(DataSource dataSource, SecretCryptoService secretCryptoService) {
        this(dataSource, secretCryptoService, null);
    }

    @Autowired
    public MysqlConnectionProbe(
        DataSource dataSource,
        SecretCryptoService secretCryptoService,
        OutboundEndpointPolicy endpointPolicy
    ) {
        this.dataSource = dataSource;
        this.secretCryptoService = secretCryptoService;
        this.endpointPolicy = endpointPolicy;
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
            validatedBaseUrl(config.getBaseUrl()),
            connectionProperties(config)
        )) {
            boolean valid = connection.isValid(2);
            return new ConnectionProbeResult(valid, valid ? "connected" : "failed", valid ? null : "MySQL connection is not valid");
        } catch (Exception ex) {
            return new ConnectionProbeResult(false, "failed", conciseError(ex));
        }
    }

    private String validatedBaseUrl(String baseUrl) {
        if (endpointPolicy != null) {
            endpointPolicy.validate(OutboundEndpointType.MYSQL, baseUrl);
        }
        return baseUrl;
    }

    Properties connectionProperties(IntegrationConfig config) {
        Properties properties = new Properties();
        if (StringUtils.hasText(config.getDefaultOwner())) {
            properties.setProperty("user", config.getDefaultOwner().trim());
        }
        String password = secretCryptoService.decrypt(config.getTokenValue());
        if (password != null) {
            properties.setProperty("password", password);
        }
        properties.setProperty("connectTimeout", CONNECT_TIMEOUT_MILLIS);
        properties.setProperty("socketTimeout", SOCKET_TIMEOUT_MILLIS);
        return properties;
    }

    private String conciseError(Exception ex) {
        return ConnectionProbeErrorMessage.concise(ex);
    }
}
