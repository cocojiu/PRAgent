package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class LlmProviderCapabilitiesTest {

    @Test
    void knownProvidersUseExplicitStructuredOutputModes() {
        assertThat(LlmProviderCapabilities.forProvider("openai").structuredOutputMode())
            .isEqualTo(LlmStructuredOutputMode.JSON_SCHEMA);
        assertThat(LlmProviderCapabilities.forProvider("dashscope").structuredOutputMode())
            .isEqualTo(LlmStructuredOutputMode.JSON_OBJECT);
        assertThat(LlmProviderCapabilities.forProvider("vendor-internal").structuredOutputMode())
            .isEqualTo(LlmStructuredOutputMode.NONE);
    }

    @Test
    void unknownProvidersDoNotReceiveResponseFormat() {
        LlmProviderCapability capability = LlmProviderCapabilities.forProvider("vendor-internal");

        assertThat(capability.responseFormat(
            LlmStructuredOutputSchemas.REVIEW_SCHEMA_NAME,
            LlmStructuredOutputSchemas.review()
        )).isEqualTo(Map.of());
    }
}
