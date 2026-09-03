package com.repoguard.agent.notification;

public record NotificationMessage(
    String eventType,
    Long taskId,
    Long batchId,
    String organization,
    String repository,
    Integer prNumber,
    String title,
    String status,
    String riskLevel,
    Integer findingCount,
    Integer commentSucceededCount,
    Integer commentFailedCount,
    Integer commentSkippedCount,
    String detailUrl,
    String alertSummary
) {

    public NotificationMessage(
        String eventType,
        Long taskId,
        Long batchId,
        String organization,
        String repository,
        Integer prNumber,
        String title,
        String status,
        String riskLevel,
        Integer findingCount,
        Integer commentSucceededCount,
        Integer commentFailedCount,
        Integer commentSkippedCount,
        String detailUrl
    ) {
        this(eventType, taskId, batchId, organization, repository, prNumber, title, status, riskLevel,
            findingCount, commentSucceededCount, commentFailedCount, commentSkippedCount, detailUrl, null);
    }
}
