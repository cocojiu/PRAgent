package com.repoguard.agent.review;

import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
class LlmFallbackReasonClassifier {

    static final String DEFAULT_REASON = "LLM review unavailable";
    static final String CONFIG_INCOMPLETE_CATEGORY = "config_incomplete";
    static final String UNAVAILABLE_CATEGORY = "llm_unavailable";
    private static final String CATEGORY_MARKER = "category=";

    String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return DEFAULT_REASON;
        }
        return reason.replaceAll("\\s+", " ").trim();
    }

    String category(String reason) {
        String normalized = normalizeReason(reason).toLowerCase(Locale.ROOT);
        int markerIndex = normalized.indexOf(CATEGORY_MARKER);
        if (markerIndex >= 0) {
            int valueStart = markerIndex + CATEGORY_MARKER.length();
            int valueEnd = normalized.indexOf(' ', valueStart);
            return valueEnd < 0 ? normalized.substring(valueStart) : normalized.substring(valueStart, valueEnd);
        }
        if (normalized.contains("config")) {
            return CONFIG_INCOMPLETE_CATEGORY;
        }
        return UNAVAILABLE_CATEGORY;
    }
}
