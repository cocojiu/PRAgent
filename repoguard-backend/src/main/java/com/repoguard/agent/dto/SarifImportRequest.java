package com.repoguard.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SarifImportRequest(
    @NotBlank @Size(max = 2_000_000) String content
) {
}
