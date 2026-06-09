package com.repoguard.agent.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record BaseSettingsRequest(
    @NotBlank @Size(max = 128) String systemName,
    @NotBlank @Size(max = 32) String language,
    @NotBlank @Size(max = 64) String timezone,
    @NotNull @Min(1) @Max(365) Integer retentionDays
) {
}
