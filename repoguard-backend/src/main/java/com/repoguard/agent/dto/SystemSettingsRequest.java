package com.repoguard.agent.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record SystemSettingsRequest(
    @Valid @NotNull BaseSettingsRequest base,
    @Valid @NotNull ReviewPolicySettingsRequest policy,
    @Valid @NotNull NotificationSettingsRequest notification,
    @Valid @NotNull SecuritySettingsRequest security
) {
}
