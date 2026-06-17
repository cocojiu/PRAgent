package com.repoguard.agent.config;

import org.springframework.util.StringUtils;

public record ReviewRuleSettings(
    String id,
    String status,
    String filePatterns
) {

    public boolean disabled() {
        return "DISABLED".equalsIgnoreCase(status);
    }

    public boolean hasFilePatterns() {
        return StringUtils.hasText(filePatterns);
    }
}
