package com.repoguard.agent.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record NotificationSettingsRequest(
    @NotNull Boolean githubComment,
    @NotNull Boolean highRiskPr,
    @NotNull Boolean failedTask,
    @Size(max = 255) String email
) {
}
