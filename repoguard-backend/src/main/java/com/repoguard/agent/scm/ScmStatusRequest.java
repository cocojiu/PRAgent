package com.repoguard.agent.scm;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ScmStatusRequest(
    @Size(max = 128) String name,
    @NotBlank @Size(max = 32) String state,
    @Size(max = 512) String description,
    @Size(max = 1024) String targetUrl
) {
}
