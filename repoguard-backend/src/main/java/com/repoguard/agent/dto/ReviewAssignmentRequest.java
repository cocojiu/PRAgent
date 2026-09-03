package com.repoguard.agent.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record ReviewAssignmentRequest(
    @Size(max = 128) String assignee,
    @Min(5) @Max(10080) Integer slaMinutes
) {
}
