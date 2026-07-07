package com.repoguard.agent.messaging;

import java.util.Objects;

public record RabbitPublishCompensationOutcome(
    RabbitPublishFailurePhase failurePhase,
    boolean success,
    String reason
) {
    private static final String NONE = "none";

    public RabbitPublishCompensationOutcome {
        Objects.requireNonNull(failurePhase, "failurePhase");
        reason = reason == null || reason.isBlank() ? NONE : reason.trim();
        if (!success && NONE.equals(reason)) {
            throw new IllegalArgumentException("Failed Rabbit publish compensation outcome requires reason");
        }
    }

    public static RabbitPublishCompensationOutcome succeeded(RabbitPublishFailurePhase failurePhase) {
        return new RabbitPublishCompensationOutcome(failurePhase, true, NONE);
    }

    public static RabbitPublishCompensationOutcome failed(RabbitPublishFailurePhase failurePhase, String reason) {
        return new RabbitPublishCompensationOutcome(failurePhase, false, reason);
    }
}
