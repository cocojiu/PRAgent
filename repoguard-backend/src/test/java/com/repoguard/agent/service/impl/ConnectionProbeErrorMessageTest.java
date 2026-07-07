package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ConnectionProbeErrorMessageTest {

    @Test
    void conciseUsesCauseMessageWhenTopLevelMessageIsMissing() {
        RuntimeException failure = new RuntimeException(null, new IllegalStateException("nested failure"));

        assertThat(ConnectionProbeErrorMessage.concise(failure)).isEqualTo("nested failure");
    }

    @Test
    void conciseSanitizesSensitiveValuesAndNormalizesWhitespace() {
        RuntimeException failure = new RuntimeException(
            "jdbc:mysql://user:raw-password@internal/repoguard\npassword=secret token=raw-token Bearer abc.def"
        );

        assertThat(ConnectionProbeErrorMessage.concise(failure))
            .isEqualTo("jdbc:mysql://user:****@internal/repoguard password=**** token=**** Bearer ****");
    }

    @Test
    void conciseFallsBackToExceptionClassNameWhenMessageIsMissing() {
        assertThat(ConnectionProbeErrorMessage.concise(new IllegalStateException()))
            .isEqualTo("IllegalStateException");
    }

    @Test
    void conciseTruncatesLongMessages() {
        String result = ConnectionProbeErrorMessage.concise(new RuntimeException("a".repeat(260)));

        assertThat(result).hasSize(240).endsWith("...");
    }
}
