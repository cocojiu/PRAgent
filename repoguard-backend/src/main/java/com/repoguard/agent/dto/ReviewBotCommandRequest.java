package com.repoguard.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ReviewBotCommandRequest(
    @NotBlank @Size(max = 128) String externalCommandId,
    @NotBlank @Size(max = 512) String text,
    Long taskId
) {
}
