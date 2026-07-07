package com.repoguard.agent.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RabbitPublishCompensationOutcomeTest {

    @Test
    void succeededOutcomeUsesPhaseAndNoReason() {
        RabbitPublishCompensationOutcome outcome =
            RabbitPublishCompensationOutcome.succeeded(RabbitPublishFailurePhase.NOTIFICATION);

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.failurePhase().code()).isEqualTo("notification");
        assertThat(outcome.reason()).isEqualTo("none");
    }

    @Test
    void failedOutcomeRequiresReason() {
        RabbitPublishCompensationOutcome outcome =
            RabbitPublishCompensationOutcome.failed(RabbitPublishFailurePhase.PUBLISH, " confirm_timeout ");

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.failurePhase()).isEqualTo(RabbitPublishFailurePhase.PUBLISH);
        assertThat(outcome.reason()).isEqualTo("confirm_timeout");
    }

    @Test
    void failedOutcomeRejectsBlankReason() {
        assertThatThrownBy(() -> RabbitPublishCompensationOutcome.failed(RabbitPublishFailurePhase.PUBLISH, " "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Failed Rabbit publish compensation outcome requires reason");
    }

    @Test
    void phaseCanBeResolvedFromStableCode() {
        assertThat(RabbitPublishFailurePhase.fromCode("execute")).isEqualTo(RabbitPublishFailurePhase.EXECUTE);
    }

    @Test
    void phaseRejectsUnknownCode() {
        assertThatThrownBy(() -> RabbitPublishFailurePhase.fromCode("unknown"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Unknown Rabbit publish failure phase: unknown");
    }
}
