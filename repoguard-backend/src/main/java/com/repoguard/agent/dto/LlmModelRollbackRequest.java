package com.repoguard.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LlmModelRollbackRequest(
    @NotBlank @Size(max = 512) String reason
) {
}
