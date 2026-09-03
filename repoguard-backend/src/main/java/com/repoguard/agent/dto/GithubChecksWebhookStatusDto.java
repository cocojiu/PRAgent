package com.repoguard.agent.dto;

public record GithubChecksWebhookStatusDto(
    String endpointUrl,
    boolean enabled,
    boolean signatureRequired,
    boolean secretConfigured,
    boolean repositoriesRestricted,
    boolean branchesRestricted,
    String lastDeliveryId,
    String lastDeliveryStatus,
    String lastDeliveryAt
) {
}
