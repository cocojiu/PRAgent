package com.repoguard.agent.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record EnterpriseTenantCreateRequest(
    @NotBlank @Pattern(regexp = "^[a-z0-9][a-z0-9-]{1,62}$") String tenantKey,
    @NotBlank @Size(max = 128) String displayName,
    @NotNull @Min(1) Long initialAdminUserId
) {
}
