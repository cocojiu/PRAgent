package com.repoguard.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AuthPasswordChangeRequest(
    @NotBlank @Size(min = 8, max = 128) String currentPassword,
    @NotBlank @Size(min = 8, max = 128) String newPassword,
    @NotBlank @Size(min = 8, max = 128) String confirmPassword
) {
}
