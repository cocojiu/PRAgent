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
    ReviewTimelineItem latestTimeline,
    Boolean humanReviewRequired,
    String humanReviewStatus,
    String humanReviewNote,
    String humanReviewBy,
    String humanReviewedAt
) {
    public ReviewTaskStatusResponse(
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
        this(
            id,
            status,
            riskLevel,
            llmStatus,
            duration,
            updatedAt,
            failureCategory,
            failureReason,
            failureSuggestion,
            latestTimeline,
            false,
            "not_required",
            null,
            null,
            null
        );
    }
}
