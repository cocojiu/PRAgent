package com.repoguard.agent.messaging;

import java.util.Objects;

public enum RabbitPublishFailurePhase {
    PUBLISH("publish"),
    NOTIFICATION("notification"),
    EXECUTE("execute");

    private final String code;

    RabbitPublishFailurePhase(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static RabbitPublishFailurePhase fromCode(String code) {
        String normalized = Objects.requireNonNull(code, "code").trim();
        for (RabbitPublishFailurePhase phase : values()) {
            if (phase.code.equals(normalized)) {
                return phase;
            }
        }
        throw new IllegalArgumentException("Unknown Rabbit publish failure phase: " + code);
    }
}
