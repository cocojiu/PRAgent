package com.repoguard.agent.dto;

public record ReviewBotCommandResponse(
    String provider,
    String command,
    String status,
    Long taskId,
    String message
) {
}
