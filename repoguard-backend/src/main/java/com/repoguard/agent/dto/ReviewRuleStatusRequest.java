package com.repoguard.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record ReviewRuleStatusRequest(
    @NotBlank @Pattern(regexp = "(?i)enabled|disabled") String status
) {
}
