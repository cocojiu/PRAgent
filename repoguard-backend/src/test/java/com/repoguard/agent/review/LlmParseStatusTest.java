package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LlmParseStatusTest {

    @Test
    void parsesKnownStatusesCaseInsensitiveAndTrimmed() {
        assertThat(LlmParseStatus.from(" parsed ")).isEqualTo(LlmParseStatus.PARSED);
        assertThat(LlmParseStatus.from(" FALLBACK ")).isEqualTo(LlmParseStatus.FALLBACK);
        assertThat(LlmParseStatus.PARTIAL_FALLBACK.is(" partial_FALLBACK ")).isTrue();
    }

    @Test
    void returnsUnknownForMissingOrUnexpectedStatus() {
        assertThat(LlmParseStatus.from(null)).isEqualTo(LlmParseStatus.UNKNOWN);
        assertThat(LlmParseStatus.from("")).isEqualTo(LlmParseStatus.UNKNOWN);
        assertThat(LlmParseStatus.from("ok")).isEqualTo(LlmParseStatus.UNKNOWN);
        assertThat(LlmParseStatus.PARTIAL_FALLBACK.is(null)).isFalse();
        assertThat(LlmParseStatus.PARTIAL_FALLBACK.is("")).isFalse();
        assertThat(LlmParseStatus.PARTIAL_FALLBACK.is("fallback")).isFalse();
    }

    @Test
    void dtoCodeKeepsMissingStatusNullButNormalizesUnexpectedStatus() {
        assertThat(LlmParseStatus.dtoCodeOrNull(null)).isNull();
        assertThat(LlmParseStatus.dtoCodeOrNull(" ")).isNull();
        assertThat(LlmParseStatus.dtoCodeOrNull(" PARSED ")).isEqualTo("parsed");
        assertThat(LlmParseStatus.dtoCodeOrNull("ok")).isEqualTo("unknown");
    }
}
