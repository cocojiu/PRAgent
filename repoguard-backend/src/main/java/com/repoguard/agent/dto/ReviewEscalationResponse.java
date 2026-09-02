package com.repoguard.agent.dto;

public record ReviewEscalationResponse(
    int escalated,
    int skipped,
    String executedAt
) {
}
