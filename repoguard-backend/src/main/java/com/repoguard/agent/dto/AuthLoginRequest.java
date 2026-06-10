package com.repoguard.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AuthLoginRequest(
    @NotBlank
    @Size(max = 255)
    String account,

    @NotBlank
    @Size(max = 128)
    String password,

    Boolean remember
) {
}
