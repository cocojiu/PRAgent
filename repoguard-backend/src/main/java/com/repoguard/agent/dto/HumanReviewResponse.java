package com.repoguard.agent.dto;

public record HumanReviewResponse(
    Long taskId,
    String status,
    Boolean humanReviewRequired,
    String humanReviewStatus,
    String humanReviewNote,
    String humanReviewBy,
    String humanReviewedAt,
    String message
) {
}
