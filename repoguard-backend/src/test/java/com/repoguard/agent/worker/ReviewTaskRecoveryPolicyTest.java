package com.repoguard.agent.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.repoguard.agent.config.RabbitReviewQueueProperties;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class ReviewTaskRecoveryPolicyTest {

    @Test
    void normalizesExecutionTimeoutAndBatchSizeWithLowerBounds() {
        RabbitReviewQueueProperties properties = new RabbitReviewQueueProperties();
        properties.setReviewExecutionTimeoutMs(1);
        properties.setReviewRecoveryBatchSize(0);
        ReviewTaskRecoveryPolicy policy = new ReviewTaskRecoveryPolicy(properties);

        assertThat(policy.executionTimeoutMs()).isEqualTo(60000);
        assertThat(policy.batchSize()).isEqualTo(1);
        assertThat(policy.publishRetryDelayMs()).isEqualTo(60000);
    }

    @Test
    void usesConfiguredExecutionTimeoutAndBatchSizeWhenValid() {
        RabbitReviewQueueProperties properties = new RabbitReviewQueueProperties();
        properties.setReviewExecutionTimeoutMs(120000);
        properties.setReviewRecoveryBatchSize(25);
        properties.setPublishCompensationIntervalMs(5000);
        ReviewTaskRecoveryPolicy policy = new ReviewTaskRecoveryPolicy(properties);
        LocalDateTime now = LocalDateTime.parse("2026-07-05T00:20:00");

        assertThat(policy.executionTimeoutMs()).isEqualTo(120000);
        assertThat(policy.batchSize()).isEqualTo(25);
        assertThat(policy.publishRetryDelayMs()).isEqualTo(5000);
        assertThat(policy.expiredBefore(now)).isEqualTo(LocalDateTime.parse("2026-07-05T00:18:00"));
    }

    @Test
    void requiresPropertiesDependency() {
        assertThatThrownBy(() -> new ReviewTaskRecoveryPolicy(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("properties");
    }
}
