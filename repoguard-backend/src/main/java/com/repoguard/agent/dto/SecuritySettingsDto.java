package com.repoguard.agent.dto;

public record SecuritySettingsDto(
    Boolean webhookSignature,
    Boolean secretMasking,
    Boolean publicRepoAllowed,
    Integer tokenTtlDays
) {
}
