package com.repoguard.agent.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record EnterpriseTenantMembershipRequest(
    @NotNull @Min(1) Long userId,
    @NotBlank @Pattern(regexp = "^(ADMIN|VIEWER)$") String role,
    boolean defaultTenant
) {
}
