package com.repoguard.agent.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record GithubChecksPolicyRequest(
    @NotBlank @Size(max = 255) String organization,
    @NotBlank @Size(max = 255) String repository,
    boolean enabled,
    @NotNull @Min(0) Long expectedVersion,
    @NotNull @AssertTrue Boolean confirmed
) {
}
