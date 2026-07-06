package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LlmFallbackReasonClassifierTest {

    private final LlmFallbackReasonClassifier classifier = new LlmFallbackReasonClassifier();

    @Test
    void normalizesBlankAndWhitespaceReason() {
        assertThat(classifier.normalizeReason(null)).isEqualTo(LlmFallbackReasonClassifier.DEFAULT_REASON);
        assertThat(classifier.normalizeReason("  LLM   request \n timed out  "))
            .isEqualTo("LLM request timed out");
    }

    @Test
    void extractsExplicitCategoryMarker() {
        assertThat(classifier.category("LLM external call failed: category=llm_rate_limited retryable=true"))
            .isEqualTo("llm_rate_limited");
        assertThat(classifier.category("CATEGORY=LLM_AUTH_FAILED"))
            .isEqualTo("llm_auth_failed");
    }

    @Test
    void classifiesConfigurationReasonSeparately() {
        assertThat(classifier.category("LLM config is incomplete"))
            .isEqualTo(LlmFallbackReasonClassifier.CONFIG_INCOMPLETE_CATEGORY);
    }

    @Test
    void fallsBackToUnavailableCategory() {
        assertThat(classifier.category("upstream connection failed"))
            .isEqualTo(LlmFallbackReasonClassifier.UNAVAILABLE_CATEGORY);
        assertThat(classifier.category(null))
            .isEqualTo(LlmFallbackReasonClassifier.UNAVAILABLE_CATEGORY);
    }
}
