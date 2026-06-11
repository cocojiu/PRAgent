package com.repoguard.agent.dto;

import jakarta.validation.constraints.NotBlank;

public record AuthRefreshTokenResetRequest(
    @NotBlank String account,
    @NotBlank String password,
    Boolean remember
) {
}
