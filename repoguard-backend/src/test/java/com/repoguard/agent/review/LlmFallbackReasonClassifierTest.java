package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.external.ExternalCallException;
import org.junit.jupiter.api.Test;

class LlmFallbackReasonClassifierTest {

    private final LlmFallbackReasonClassifier classifier = new LlmFallbackReasonClassifier();

    @Test
    void normalizesBlankAndWhitespaceReason() {
        assertThat(classifier.normalizeReason((String) null)).isEqualTo(LlmFallbackReasonClassifier.DEFAULT_REASON);
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
        assertThat(classifier.category((String) null))
            .isEqualTo(LlmFallbackReasonClassifier.UNAVAILABLE_CATEGORY);
    }

    @Test
    void classifiesExternalCallExceptionByMessageCategory() {
        ExternalCallException failure = new ExternalCallException(
            "LLM",
            "llm_rate_limited",
            true,
            429,
            "operation=chat_completions",
            null
        );

        assertThat(classifier.category(failure)).isEqualTo("llm_rate_limited");
        assertThat(classifier.normalizeReason(failure)).contains("category=llm_rate_limited", "status=429");
    }

    @Test
    void classifiesInternalExceptionSeparatelyFromUnavailable() {
        assertThat(classifier.category(new NullPointerException()))
            .isEqualTo(LlmFallbackReasonClassifier.INTERNAL_ERROR_CATEGORY);
        assertThat(classifier.category(new IllegalStateException("chunk merge failed")))
            .isEqualTo(LlmFallbackReasonClassifier.INTERNAL_ERROR_CATEGORY);
        assertThat(classifier.normalizeReason(new NullPointerException()))
            .isEqualTo("internal error: NullPointerException");
        assertThat(classifier.normalizeReason(new IllegalStateException("chunk merge failed")))
            .isEqualTo("internal error: IllegalStateException: chunk merge failed");
    }
}
