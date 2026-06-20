package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LlmStatusTest {

    @Test
    void fromNormalizesStoredStatusCodes() {
        assertThat(LlmStatus.from("pending")).isEqualTo(LlmStatus.PENDING);
        assertThat(LlmStatus.from(" completed ")).isEqualTo(LlmStatus.COMPLETED);
        assertThat(LlmStatus.from("fallback")).isEqualTo(LlmStatus.FALLBACK);
        assertThat(LlmStatus.from(null)).isEqualTo(LlmStatus.UNKNOWN);
        assertThat(LlmStatus.from("skipped")).isEqualTo(LlmStatus.UNKNOWN);
    }
}
