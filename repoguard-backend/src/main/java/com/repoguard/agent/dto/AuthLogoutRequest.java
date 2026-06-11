package com.repoguard.agent.dto;

import jakarta.validation.constraints.NotBlank;

public record AuthLogoutRequest(
    @NotBlank String refreshToken
) {
}
