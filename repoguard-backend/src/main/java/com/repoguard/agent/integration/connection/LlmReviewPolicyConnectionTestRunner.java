package com.repoguard.agent.integration.connection;

import com.repoguard.agent.dto.ConnectionTestResultDto;
import com.repoguard.agent.entity.ReviewPolicyConfig;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Runs LLM review policy connectivity checks and assembles the public connection test response.
 */
class LlmReviewPolicyConnectionTestRunner {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ConnectionProbe<ReviewPolicyConfig> llmConnectionProbe;

    LlmReviewPolicyConnectionTestRunner(ConnectionProbe<ReviewPolicyConfig> llmConnectionProbe) {
        this.llmConnectionProbe = llmConnectionProbe;
    }

    ConnectionTestResultDto run(ReviewPolicyConfig configToProbe) {
        if (configToProbe == null) {
            return connectionResult(false, "failed", "LLM config is not configured");
        }
        ConnectionProbeResult result = llmConnectionProbe.probe(configToProbe);
        return connectionResult(Boolean.TRUE.equals(result.healthy()), result.status(), result.message());
    }

    private ConnectionTestResultDto connectionResult(boolean success, String status, String message) {
        return new ConnectionTestResultDto(success, status, message, format(LocalDateTime.now()), null, null, null, null, null, null);
    }

    private String format(LocalDateTime time) {
        return time == null ? null : time.format(DATE_TIME_FORMATTER);
    }
}
