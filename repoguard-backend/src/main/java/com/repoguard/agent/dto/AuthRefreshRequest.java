package com.repoguard.agent.dto;

import jakarta.validation.constraints.NotBlank;

public record AuthRefreshRequest(
    @NotBlank String refreshToken
) {
}
