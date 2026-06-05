package com.repoguard.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record GithubIntegrationConfigRequest(
    @NotBlank @Size(max = 512) String baseUrl,
    @Size(max = 4096) String token,
    @Size(max = 128) String defaultOwner,
    @Size(max = 128) String defaultRepo
) {
}
