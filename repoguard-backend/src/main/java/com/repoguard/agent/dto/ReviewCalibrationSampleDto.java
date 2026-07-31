package com.repoguard.agent.dto;

public record ReviewCalibrationSampleDto(
    long findingId,
    long taskId,
    Integer prNumber,
    String title,
    String repository,
    String organization,
    String commitSha,
    String prUrl,
    String taskCreatedAt,
    String source,
    String ruleId,
    String severity,
    String confidence,
    String filePath,
    Integer lineNumber,
    String message,
    String evidence,
    String impact,
    String recommendation,
    String preconditions,
    String issueType,
    String verificationStatus,
    boolean blockingCandidate,
    String enforcementMode,
    String feedbackStatus,
    String versionKey
) {
}
