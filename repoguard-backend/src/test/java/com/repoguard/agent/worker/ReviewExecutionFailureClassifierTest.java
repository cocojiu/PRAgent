package com.repoguard.agent.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.external.ExternalCallException;
import org.junit.jupiter.api.Test;

class ReviewExecutionFailureClassifierTest {

    private final ReviewExecutionFailureClassifier classifier = new ReviewExecutionFailureClassifier();

    @Test
    void usesExternalCallCategoryWhenAvailable() {
        RuntimeException failure = new ExternalCallException(
            "github",
            "github_timeout",
            true,
            null,
            "timeout",
            new IllegalStateException("socket timeout")
        );

        assertThat(classifier.failureCategory(failure)).isEqualTo("github_timeout");
    }

    @Test
    void fallsBackToExceptionSimpleName() {
        assertThat(classifier.failureCategory(new IllegalStateException("boom")))
            .isEqualTo("IllegalStateException");
    }
}
