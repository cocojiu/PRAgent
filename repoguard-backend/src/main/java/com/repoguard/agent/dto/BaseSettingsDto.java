package com.repoguard.agent.dto;

public record BaseSettingsDto(
    String systemName,
    String language,
    String timezone,
    Integer retentionDays
) {
}
