package com.repoguard.agent.review.quality;

import java.util.Locale;

/**
 * Non-sensitive, aggregate metadata for one real PR evaluation sample.
 * It intentionally stores labels and counts rather than source content or repository identifiers.
 */
public record LlmEvaluationSampleContext(
    String language,
    int changedFileCount,
    int changedLineCount,
    String fileTypeGroup,
    String expectedLocationKey
) {

    public LlmEvaluationSampleContext {
        language = normalizeToken(language, "language", "unknown");
        fileTypeGroup = normalizeToken(fileTypeGroup, "fileTypeGroup", "unknown");
        expectedLocationKey = normalizeOptionalToken(expectedLocationKey, "expectedLocationKey");
        if (changedFileCount < 0) {
            throw new IllegalArgumentException("LLM evaluation changedFileCount must not be negative");
        }
        if (changedLineCount < 0) {
            throw new IllegalArgumentException("LLM evaluation changedLineCount must not be negative");
        }
    }

    public static LlmEvaluationSampleContext unknown() {
        return new LlmEvaluationSampleContext("unknown", 0, 0, "unknown", "");
    }

    /**
     * Returns whether this context has enough aggregate labels for a real-data baseline.
     */
    public boolean complete(boolean expectedFinding) {
        return !"unknown".equals(language)
            && changedFileCount > 0
            && !"unknown".equals(fileTypeGroup)
            && (!expectedFinding || !expectedLocationKey.isBlank());
    }

    private static String normalizeToken(String value, String field, String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9][a-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException(
                "LLM evaluation " + field + " must be an anonymized token"
            );
        }
        return normalized;
    }

    private static String normalizeOptionalToken(String value, String field) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return normalizeToken(value, field, "");
    }
}
