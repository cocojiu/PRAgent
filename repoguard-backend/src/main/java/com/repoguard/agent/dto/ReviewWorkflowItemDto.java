package com.repoguard.agent.dto;

public record ReviewWorkflowItemDto(
    Long taskId,
    String repository,
    Integer prNumber,
    String title,
    String status,
    String humanReviewStatus,
    String assignee,
    String assignedAt,
    String slaDeadline,
    Integer escalationLevel,
    boolean overdue
) {
}
