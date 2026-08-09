package com.repoguard.agent.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record ReviewStrategyRollbackRequest(
    @NotNull @Min(1) Long expectedSnapshotId
) {
}
