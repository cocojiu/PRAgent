package com.repoguard.agent.dto;

public record NotificationSettingsDto(
    Boolean githubComment,
    Boolean highRiskPr,
    Boolean failedTask,
    String email
) {
}
