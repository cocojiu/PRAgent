package com.repoguard.agent.dto;

public record NotificationBindingDto(
    Long id,
    String name,
    String provider,
    String organization,
    String repository,
    Boolean enabled,
    String webhookUrl,
    String secret,
    Boolean notifyReviewCompleted,
    Boolean notifyReviewFailed,
    Boolean notifyHumanReviewRequired,
    Boolean notifyGithubComment,
    String status,
    String lastCheckedAt,
    String lastError,
    String createdAt,
    String updatedAt
) {
}
