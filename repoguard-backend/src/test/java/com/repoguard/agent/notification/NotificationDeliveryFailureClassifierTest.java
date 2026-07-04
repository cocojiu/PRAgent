package com.repoguard.agent.notification;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NotificationDeliveryFailureClassifierTest {

    private final NotificationDeliveryFailureClassifier classifier = new NotificationDeliveryFailureClassifier();

    @Test
    void fallsBackToExceptionSimpleName() {
        assertThat(classifier.failureCategory(new IllegalStateException("boom")))
            .isEqualTo("IllegalStateException");
    }
}
