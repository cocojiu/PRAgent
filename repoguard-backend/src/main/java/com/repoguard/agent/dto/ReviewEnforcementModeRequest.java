package com.repoguard.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

public record ReviewEnforcementModeRequest(
    @NotBlank @Pattern(regexp = "(?i)observe|comment|block") String enforcementMode,
    @NotNull @Min(1) Long expectedSnapshotId
) {
}
