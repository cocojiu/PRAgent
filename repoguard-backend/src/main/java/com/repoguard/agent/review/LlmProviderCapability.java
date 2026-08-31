package com.repoguard.agent.review;

import java.util.Map;

/**
 * Provider capability used to decide whether an OpenAI-compatible response_format can be sent.
 * Unknown providers intentionally return {@link LlmStructuredOutputMode#NONE} and continue to
 * use the existing parser/repair path.
 */
public record LlmProviderCapability(
    String provider,
    LlmStructuredOutputMode structuredOutputMode
) {

    public LlmProviderCapability {
        provider = provider == null ? "unknown" : provider.trim().toLowerCase(java.util.Locale.ROOT);
        structuredOutputMode = structuredOutputMode == null ? LlmStructuredOutputMode.NONE : structuredOutputMode;
    }

    public boolean supportsStructuredOutput() {
        return structuredOutputMode.enabled();
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
