package com.repoguard.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record UserStatusUpdateRequest(
    @NotBlank
    @Pattern(regexp = "ACTIVE|DISABLED")
    String status
) {
}
