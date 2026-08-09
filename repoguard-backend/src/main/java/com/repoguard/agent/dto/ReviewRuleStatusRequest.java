package com.repoguard.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

public record ReviewRuleStatusRequest(
    @NotBlank @Pattern(regexp = "(?i)enabled|disabled") String status,
    @NotNull @Min(1) Long expectedPolicyVersion
) {
}
