package com.repoguard.agent.integration.connection;

import com.repoguard.agent.dto.ConnectionTestResultDto;
import com.repoguard.agent.entity.IntegrationConfig;
import com.repoguard.agent.external.ExternalCallErrorClassifier;
import com.repoguard.agent.external.ExternalCallException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.BiConsumer;
import org.springframework.web.client.RestClientResponseException;

/**
 * Runs GitHub integration connectivity checks and applies saved-config status updates.
 */
class GithubIntegrationConnectionTestRunner {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ConnectionProbe<IntegrationConfig> githubConnectionProbe;

    GithubIntegrationConnectionTestRunner(ConnectionProbe<IntegrationConfig> githubConnectionProbe) {
        this.githubConnectionProbe = githubConnectionProbe;
    }

    ConnectionTestResultDto run(
        IntegrationConfig configToProbe,
        boolean transientConfig,
        BiConsumer<IntegrationConfig, String> markChecked
    ) {
        if (configToProbe == null) {
            return connectionResult(false, "failed", "GitHub integration is not configured");
        }
        try {
            ConnectionProbeResult result = githubConnectionProbe.probe(configToProbe);
            if (!transientConfig) {
                markChecked.accept(
                    configToProbe,
                    Boolean.TRUE.equals(result.healthy()) ? null : failureMessage(result)
                );
            }
            return connectionResult(Boolean.TRUE.equals(result.healthy()), result.status(), result.message());
        } catch (RuntimeException ex) {
            String error = conciseError(classifyGithubHttpFailure(ex));
            if (!transientConfig) {
                markChecked.accept(configToProbe, error);
            }
            return connectionResult(false, "failed", error);
        }
    }

    private ConnectionTestResultDto connectionResult(boolean success, String status, String message) {
        return new ConnectionTestResultDto(success, status, message, format(LocalDateTime.now()), null, null, null, null, null, null);
    }

    private String failureMessage(ConnectionProbeResult result) {
        return result.message() == null || result.message().isBlank()
            ? "GitHub connection test failed"
            : result.message();
    }

    private RuntimeException classifyGithubHttpFailure(RuntimeException ex) {
        if (ex instanceof ExternalCallException || ex instanceof RestClientResponseException) {
            return ExternalCallErrorClassifier.github(ex);
        }
        return ex;
    }

    private String format(LocalDateTime time) {
        return time == null ? null : time.format(DATE_TIME_FORMATTER);
    }

    private String conciseError(Exception ex) {
        return ConnectionProbeErrorMessage.concise(ex);
    }
}
