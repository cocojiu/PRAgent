package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LlmParseStatusTest {

    @Test
    void partialFallbackMatchesCaseInsensitiveTrimmedStatus() {
        assertThat(LlmParseStatus.PARTIAL_FALLBACK.is(" partial_FALLBACK ")).isTrue();
    }

    @Test
    void partialFallbackRejectsMissingOrDifferentStatus() {
        assertThat(LlmParseStatus.PARTIAL_FALLBACK.is(null)).isFalse();
        assertThat(LlmParseStatus.PARTIAL_FALLBACK.is("")).isFalse();
        assertThat(LlmParseStatus.PARTIAL_FALLBACK.is("fallback")).isFalse();
    }
}
