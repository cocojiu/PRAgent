package com.repoguard.agent.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record EnterpriseTenantStatusRequest(
    @NotBlank @Pattern(regexp = "ACTIVE|SUSPENDED") String expectedStatus,
    @NotBlank @Pattern(regexp = "ACTIVE|SUSPENDED") String targetStatus,
    @NotNull @Min(1) Long expectedVersion,
    @NotBlank @Size(max = 512) String reason
) {
}
