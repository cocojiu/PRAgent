package com.repoguard.agent.dto;

import jakarta.validation.constraints.Size;

public record AuthLogoutRequest(
    @Size(max = 512)
    String refreshToken
) {
}
