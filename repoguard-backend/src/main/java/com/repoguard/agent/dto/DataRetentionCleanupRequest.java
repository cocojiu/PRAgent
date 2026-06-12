package com.repoguard.agent.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record DataRetentionCleanupRequest(
    @Min(1) @Max(365) Integer retentionDays,
    @Min(1) @Max(5000) Integer maxTasks,
    Boolean execute,
    String confirmText
) {
}
