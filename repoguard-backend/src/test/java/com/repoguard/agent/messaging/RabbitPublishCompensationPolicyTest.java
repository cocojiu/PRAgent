package com.repoguard.agent.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class RabbitPublishCompensationPolicyTest {

    private final RabbitPublishCompensationPolicy policy = new RabbitPublishCompensationPolicy();

    @Test
    void normalizesInvalidAttemptsBatchRetryAndLeaseValues() {
        assertThat(policy.maxAttempts(0)).isOne();
        assertThat(policy.batchSize(0)).isOne();
        assertThat(policy.retryIntervalMs(0)).isEqualTo(1000);
        assertThat(policy.leaseMs(0)).isEqualTo(1000);
    }

    @Test
    void calculatesNextAttemptFromNullableCurrentAttempt() {
        assertThat(policy.nextAttempt(null)).isOne();
        assertThat(policy.nextAttempt(4)).isEqualTo(5);
    }

    @Test
    void detectsTerminalAttemptWithNormalizedMaxAttempts() {
        assertThat(policy.isTerminalAttempt(1, 0)).isTrue();
        assertThat(policy.isTerminalAttempt(4, 5)).isFalse();
        assertThat(policy.isTerminalAttempt(5, 5)).isTrue();
    }

    @Test
    void calculatesRetryAndLeaseBoundariesFromConfiguredMilliseconds() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 6, 23, 30);

        assertThat(policy.nextRetryAt(now, 2000)).isEqualTo(LocalDateTime.of(2026, 7, 6, 23, 30, 2));
        assertThat(policy.expiredBefore(now, 3000)).isEqualTo(LocalDateTime.of(2026, 7, 6, 23, 29, 57));
    }

    @Test
    void readsAttemptsBatchAndRetryIntervalFromSharedCompensationProperties() {
        RabbitPublishCompensationProperties properties = new RabbitPublishCompensationProperties() {
            @Override
            public int getPublishCompensationMaxAttempts() {
                return 4;
            }

            @Override
            public int getPublishCompensationBatchSize() {
                return 7;
            }

            @Override
            public long getPublishCompensationIntervalMs() {
                return 2000;
            }

            @Override
            public long getPublishCompensationLeaseMs() {
                return 3000;
            }
        };
        LocalDateTime now = LocalDateTime.of(2026, 7, 6, 23, 30);

        assertThat(policy.maxAttempts(properties)).isEqualTo(4);
        assertThat(policy.batchSize(properties)).isEqualTo(7);
        assertThat(policy.nextRetryAt(now, properties)).isEqualTo(LocalDateTime.of(2026, 7, 6, 23, 30, 2));
        assertThat(policy.expiredBefore(now, properties.getPublishCompensationLeaseMs()))
            .isEqualTo(LocalDateTime.of(2026, 7, 6, 23, 29, 57));
    }
}
