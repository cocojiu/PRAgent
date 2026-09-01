package com.repoguard.agent.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public record DeclarativeRuleDryRunRequest(
    @Min(1) Long taskId,
    @NotEmpty @Size(max = 100) List<@Valid DeclarativeRuleDryRunFile> files
) {
}
