package com.repoguard.agent.dto;

public record NotificationReportDto(
    String period,
    String from,
    String to,
    long totalReviews,
    long completedReviews,
    long failedReviews,
    long pendingHumanReviews,
    long highRiskReviews,
    long overdueHumanReviews
) {
}
