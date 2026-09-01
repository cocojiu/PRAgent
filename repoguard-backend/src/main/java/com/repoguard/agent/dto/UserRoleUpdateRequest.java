package com.repoguard.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record UserRoleUpdateRequest(
    @NotBlank
    @Pattern(regexp = "ADMIN|VIEWER|PLATFORM_ADMIN|TENANT_ADMIN|RULE_ADMIN|REVIEWER|READ_ONLY")
    String role
) {
}
