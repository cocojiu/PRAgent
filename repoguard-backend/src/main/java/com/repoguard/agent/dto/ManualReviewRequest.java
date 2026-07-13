package com.repoguard.agent.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;

public record ManualReviewRequest(
    @NotBlank @Size(max = 128) String organization,
    @NotBlank @Size(max = 128) String repository,
    @NotNull @Min(1) Integer prNumber,
    @Size(max = 512) String title,
    @NotBlank @Pattern(regexp = "(?i)^[0-9a-f]{40}([0-9a-f]{24})?$") String commit,
    @Size(max = 128) String branch,
    @Size(max = 32) String source
) {
}
