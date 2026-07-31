package com.repoguard.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record ReviewEnforcementModeRequest(
    @NotBlank @Pattern(regexp = "(?i)observe|comment|block") String enforcementMode
) {
}
