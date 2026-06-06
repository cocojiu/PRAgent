package com.repoguard.agent.dto;

public record ConnectionTestResultDto(
    Boolean success,
    String status,
    String message,
    String checkedAt
) {
}
