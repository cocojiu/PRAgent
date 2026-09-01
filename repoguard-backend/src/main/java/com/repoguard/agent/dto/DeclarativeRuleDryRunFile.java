package com.repoguard.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DeclarativeRuleDryRunFile(
    @NotBlank @Size(max = 1024) String filePath,
    @NotBlank @Size(max = 512_000) String patch
) {
}
