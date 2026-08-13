package com.repoguard.agent.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record ReviewRuleRollbackRequest(
    @NotNull @Min(1) Long expectedPolicyVersion
) {
}
