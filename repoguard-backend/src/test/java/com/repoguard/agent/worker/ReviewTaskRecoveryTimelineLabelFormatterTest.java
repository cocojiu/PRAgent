package com.repoguard.agent.worker;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ReviewTaskRecoveryTimelineLabelFormatterTest {

    private final ReviewTaskRecoveryTimelineLabelFormatter formatter = new ReviewTaskRecoveryTimelineLabelFormatter();

    @Test
    void formatsRecoveryTimelineLabels() {
        assertThat(formatter.requeuePending())
            .isEqualTo("Review execution timed out; requeue pending");
        assertThat(formatter.recoveryQueued())
            .isEqualTo("Review execution timeout recovered; message requeued");
        assertThat(formatter.recoveryPublishFailed("publisher confirm timed out"))
            .isEqualTo("Review execution recovery publish failed: publisher confirm timed out");
    }
}
