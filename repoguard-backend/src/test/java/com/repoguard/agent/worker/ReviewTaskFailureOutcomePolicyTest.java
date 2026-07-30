package com.repoguard.agent.worker;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ReviewTaskFailureOutcomePolicyTest {

    private final ReviewTaskFailureOutcomePolicy policy = new ReviewTaskFailureOutcomePolicy();

    @Test
    void defaultsFailedReviewOutcome() {
        assertThat(policy.failedRiskLevel()).isEqualTo("INFO");
        assertThat(policy.failedLlmStatus()).isEqualTo("FAILED");
    }
}
