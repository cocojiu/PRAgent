package com.repoguard.agent.integration.connection;

import com.repoguard.agent.dto.ConnectionTestResultDto;
import com.repoguard.agent.entity.IntegrationConfig;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import org.springframework.util.StringUtils;

/**
 * Runs connection tests for service integrations that expose both runtime and saved/submitted config probes.
 */
class ServiceIntegrationConnectionTestRunner {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final String successMessage;
    private final String runtimeSuccessMessage;
    private final Supplier<ConnectionProbeResult> runtimeProbe;
    private final ConnectionProbe<IntegrationConfig> configuredProbe;

    ServiceIntegrationConnectionTestRunner(
        String successMessage,
        String runtimeSuccessMessage,
        Supplier<ConnectionProbeResult> runtimeProbe,
        ConnectionProbe<IntegrationConfig> configuredProbe
    ) {
        this.successMessage = successMessage;
        this.runtimeSuccessMessage = runtimeSuccessMessage;
        this.runtimeProbe = runtimeProbe;
        this.configuredProbe = configuredProbe;
    }

    ConnectionTestResultDto run(
        IntegrationConfig savedConfig,
        IntegrationConfig configToProbe,
        boolean transientConfig,
        BiConsumer<IntegrationConfig, String> markChecked
    ) {
        if (configToProbe != null) {
            ConnectionProbeResult runtimeResult = runtimeProbe.get();
            ConnectionProbeResult configuredResult = configuredProbe.probe(configToProbe);
            boolean success = Boolean.TRUE.equals(configuredResult.healthy());
            String error = success ? null : configuredResult.message();
            if (!transientConfig) {
                markChecked.accept(configToProbe, error);
            }
            return connectionResult(
                success,
                success ? "connected" : "failed",
                success ? successMessage : error,
                transientConfig ? "submitted_config" : "saved_config",
                runtimeResult,
                savedConfig,
                transientConfig ? null : success
            );
        }
        ConnectionProbeResult runtimeResult = runtimeProbe.get();
        boolean success = Boolean.TRUE.equals(runtimeResult.healthy());
        return connectionResult(
            success,
            success ? "connected" : "failed",
            success ? runtimeSuccessMessage : runtimeResult.message(),
            "runtime_config",
            runtimeResult,
            savedConfig,
            null
        );
    }

    private ConnectionTestResultDto connectionResult(
        boolean success,
        String status,
        String message,
        String testedConfigSource,
        ConnectionProbeResult runtimeProbeResult,
        IntegrationConfig savedConfig,
        Boolean testedSavedConfigHealthy
    ) {
        Boolean runtimeHealthy = runtimeProbeResult == null ? null : runtimeProbeResult.healthy();
        Boolean savedConfigHealthy = resolveSavedConfigHealthy(savedConfig, testedSavedConfigHealthy);
        return new ConnectionTestResultDto(
            success,
            status,
            message,
            format(LocalDateTime.now()),
            testedConfigSource,
            runtimeHealthy,
            savedConfigHealthy,
            mismatch(runtimeHealthy, savedConfigHealthy),
            runtimeProbeResult == null ? null : runtimeProbeResult.status(),
            savedConfig == null ? "not_configured" : lower(savedConfig.getStatus())
        );
    }

    private Boolean resolveSavedConfigHealthy(IntegrationConfig savedConfig, Boolean testedSavedConfigHealthy) {
        if (savedConfig == null) {
            return null;
        }
        if (testedSavedConfigHealthy != null) {
            return testedSavedConfigHealthy;
        }
        return "CONFIGURED".equals(savedConfig.getStatus()) && !StringUtils.hasText(savedConfig.getLastError());
    }

    private Boolean mismatch(Boolean runtimeHealthy, Boolean savedConfigHealthy) {
        if (runtimeHealthy == null || savedConfigHealthy == null) {
            return null;
        }
        return !runtimeHealthy.equals(savedConfigHealthy);
    }

    private String lower(String value) {
        return value == null ? null : value.toLowerCase();
    }

    private String format(LocalDateTime time) {
        return time == null ? null : time.format(DATE_TIME_FORMATTER);
    }
}
