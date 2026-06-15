package com.repoguard.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record NotificationBindingRequest(
    @NotBlank @Size(max = 128) String name,
    @NotBlank @Size(max = 32) String provider,
    @NotBlank @Size(max = 128) String organization,
    @NotBlank @Size(max = 128) String repository,
    @NotNull Boolean enabled,
    @Size(max = 4096) String webhookUrl,
    @Size(max = 4096) String secret,
    @NotNull Boolean notifyReviewCompleted,
    @NotNull Boolean notifyReviewFailed,
    @NotNull Boolean notifyHumanReviewRequired,
    @NotNull Boolean notifyGithubComment
) {
}
