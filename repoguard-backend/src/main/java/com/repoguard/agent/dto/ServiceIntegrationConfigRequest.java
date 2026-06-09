package com.repoguard.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ServiceIntegrationConfigRequest(
    @NotBlank @Size(max = 512) String baseUrl,
    @Size(max = 128) String username,
    @Size(max = 4096) String secret,
    @Size(max = 128) String resource
) {
}
