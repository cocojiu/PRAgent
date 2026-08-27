package com.repoguard.agent.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record EnterpriseIdentityBindingRequest(
    @NotNull @Min(1) Long userId,
    @NotBlank @Size(max = 512) String issuer,
    @NotBlank @Size(max = 255) String subject
) {
}
