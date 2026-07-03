package com.repoguard.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AuthLogoutRequest(
    @NotBlank
    @Size(max = 512)
    String refreshToken
) {
}
