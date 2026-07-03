package com.repoguard.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AuthRefreshRequest(
    @NotBlank
    @Size(max = 512)
    String refreshToken
) {
}
