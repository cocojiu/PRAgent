package com.repoguard.agent.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record GithubChecksPreviewRequest(
    @NotBlank @Size(max = 255) String organization,
    @NotBlank @Size(max = 255) String repository,
    @NotNull @Min(1) Integer pullRequestNumber
) {
}
