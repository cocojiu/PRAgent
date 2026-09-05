package com.repoguard.agent.review;

import java.util.Map;
import org.springframework.http.HttpHeaders;

public record LlmProviderCapability(
    String provider,
    LlmStructuredOutputMode structuredOutputMode
) {
    private static final int DASHSCOPE_TIMEOUT_FLOOR_SECONDS = 210;
    private static final String DASHSCOPE_WAIT_TIMEOUT_SECONDS = "30";

    public LlmProviderCapability {
        provider = provider == null ? "unknown" : provider.trim().toLowerCase(java.util.Locale.ROOT);
        structuredOutputMode = structuredOutputMode == null ? LlmStructuredOutputMode.NONE : structuredOutputMode;
    }

    public boolean supportsStructuredOutput() {
        return structuredOutputMode.enabled();
    }

    public int requestTimeoutSeconds(Integer configuredTimeoutSeconds) {
        int configured = Math.max(1, configuredTimeoutSeconds == null ? 60 : configuredTimeoutSeconds);
        return isDashScope() ? Math.max(DASHSCOPE_TIMEOUT_FLOOR_SECONDS, configured) : configured;
    }

    public void applyTransportHeaders(HttpHeaders headers) {
        if (isDashScope()) {
            headers.set("X-DashScope-Wait-Timeout", DASHSCOPE_WAIT_TIMEOUT_SECONDS);
        }
    }

    private boolean isDashScope() {
        return "dashscope".equals(provider);
    }

    public Map<String, Object> responseFormat(String schemaName, Map<String, Object> schema) {
        if (!supportsStructuredOutput()) {
            return Map.of();
        }
        if (structuredOutputMode == LlmStructuredOutputMode.JSON_OBJECT) {
            return Map.of("type", "json_object");
        }
        return Map.of(
            "type", "json_schema",
            "json_schema", Map.of(
                "name", schemaName,
                "strict", true,
                "schema", schema
            )
        );
    }
}
