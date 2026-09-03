package com.repoguard.agent.dto;

public record CiSarifCredentialResponse(
    String credential,
    long expiresAt,
    Long taskId,
    Long attemptId,
    String organization,
    String repository,
    Integer prNumber,
    String commitSha
) {
}
