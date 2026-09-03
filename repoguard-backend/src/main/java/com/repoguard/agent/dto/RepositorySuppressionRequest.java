package com.repoguard.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RepositorySuppressionRequest(
    @NotBlank @Size(max = 128) String organization,
    @NotBlank @Size(max = 255) String repository,
    @NotBlank @Size(max = 96) String ruleId,
    @Size(max = 256) String fileGlob,
    @Size(max = 256) String symbol,
    @NotBlank @Size(max = 512) String reason,
    @NotBlank @Size(max = 64) String expiresAt
) {
}
