package com.repoguard.agent.dto;

public record FindingFeedbackResponse(
    Long findingId,
    Long taskId,
    String feedbackStatus,
    String feedbackNote,
    String feedbackBy,
    String feedbackAt
) {
}
