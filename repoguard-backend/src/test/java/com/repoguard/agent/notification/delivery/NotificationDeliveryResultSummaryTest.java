package com.repoguard.agent.notification.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

class NotificationDeliveryResultSummaryTest {

    @Test
    void emptySummaryHasNoFailures() {
        NotificationDeliveryResultSummary summary = NotificationDeliveryResultSummary.empty();

        assertThat(summary.attemptedCount()).isZero();
        assertThat(summary.successCount()).isZero();
        assertThat(summary.failureCount()).isZero();
        assertThat(summary.anyFailed()).isFalse();
    }

    @Test
    void aggregatesSuccessfulAndFailedResults() {
        NotificationDeliveryResultSummary summary = NotificationDeliveryResultSummary.empty()
            .add(NotificationSendResult.success("request-1", "ok"))
            .add(NotificationSendResult.failed("request-2", "timeout"))
            .add(NotificationSendResult.success("request-3", "ok"));

        assertThat(summary.attemptedCount()).isEqualTo(3);
        assertThat(summary.successCount()).isEqualTo(2);
        assertThat(summary.failureCount()).isOne();
        assertThat(summary.anyFailed()).isTrue();
    }

    @Test
    void rejectsNullResult() {
        NotificationDeliveryResultSummary summary = NotificationDeliveryResultSummary.empty();

        assertThatNullPointerException()
            .isThrownBy(() -> summary.add(null))
            .withMessage("result must not be null");
    }
}
