package com.repoguard.agent.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.review.RiskLevelRanker;
import org.junit.jupiter.api.Test;

class ReviewHumanReviewDecisionPolicyTest {

    private final ReviewHumanReviewDecisionPolicy policy =
        new ReviewHumanReviewDecisionPolicy(new RiskLevelRanker());

    @Test
    void requiresHumanReviewForHighAndCriticalRiskByDefault() {
        assertThat(policy.requiresHumanReview("LOW")).isFalse();
        assertThat(policy.requiresHumanReview("MEDIUM")).isFalse();
        assertThat(policy.requiresHumanReview("HIGH")).isTrue();
        assertThat(policy.requiresHumanReview("CRITICAL")).isTrue();
    }

    @Test
    void doesNotRequireHumanReviewForUnknownRisk() {
        assertThat(policy.requiresHumanReview((String) null)).isFalse();
        assertThat(policy.requiresHumanReview("unknown")).isFalse();
    }
}
