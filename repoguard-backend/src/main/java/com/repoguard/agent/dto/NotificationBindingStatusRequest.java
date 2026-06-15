package com.repoguard.agent.dto;

import jakarta.validation.constraints.NotNull;

public record NotificationBindingStatusRequest(
    @NotNull Boolean enabled
) {
}
