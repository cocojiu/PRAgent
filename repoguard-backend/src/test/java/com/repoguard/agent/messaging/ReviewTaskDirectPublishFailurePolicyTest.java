package com.repoguard.agent.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.repoguard.agent.review.task.ReviewTaskDirectPublishFailurePolicy;
import org.junit.jupiter.api.Test;

class ReviewTaskDirectPublishFailurePolicyTest {

    @Test
    void directPublishPolicyClearsLlmQualityWithoutClosingCurrentTimeline() {
        ReviewTaskDirectPublishFailurePolicy policy = ReviewTaskDirectPublishFailurePolicy.directPublish(60000);

        assertThat(policy.normalizedRetryDelayMs()).isEqualTo(60000);
        assertThat(policy.timelinePrefix()).isEqualTo("Message publish failed: ");
        assertThat(policy.clearLlmQuality()).isTrue();
        assertThat(policy.closeCurrentTimeline()).isFalse();
    }

    @Test
    void manualRequeuePolicyClosesCurrentTimelineWithoutClearingLlmQuality() {
        ReviewTaskDirectPublishFailurePolicy policy = ReviewTaskDirectPublishFailurePolicy.manualRequeue(0);

        assertThat(policy.normalizedRetryDelayMs()).isEqualTo(1000);
        assertThat(policy.timelinePrefix()).isEqualTo("Message manual requeue failed: ");
        assertThat(policy.clearLlmQuality()).isFalse();
        assertThat(policy.closeCurrentTimeline()).isTrue();
    }

    @Test
    void rejectsMissingTimelinePrefix() {
        assertThatThrownBy(() -> new ReviewTaskDirectPublishFailurePolicy(1000, null, true, false))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("timelinePrefix");
    }
}
