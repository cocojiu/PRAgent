package com.repoguard.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record NotificationReadRequest(
    @NotBlank @Size(max = 191) String notificationKey
) {
}
