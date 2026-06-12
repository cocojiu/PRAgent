package com.repoguard.agent.dto;

public record ReviewTaskStatusResponse(
    Long id,
    String status,
    String riskLevel,
    String llmStatus,
    String duration,
    String updatedAt,
    String failureCategory,
    String failureReason,
    String failureSuggestion,
    ReviewTimelineItem latestTimeline
) {
}
