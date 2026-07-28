package com.repoguard.agent.settings;

public record SystemSettings(
    boolean exists,
    String systemName,
    String language,
    String timezone,
    Integer retentionDays,
    Integer maxDiffLines,
    Boolean autoComment,
    Boolean autoRetry,
    Boolean githubComment,
    Boolean highRiskPr,
    Boolean failedTask,
    String notificationEmail,
    Boolean webhookSignature,
    Boolean secretMasking,
    Boolean publicRepoAllowed,
    Integer tokenTtlDays
) {

    public static SystemSettings empty() {
        return new SystemSettings(
            false,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null
        );
    }
}
