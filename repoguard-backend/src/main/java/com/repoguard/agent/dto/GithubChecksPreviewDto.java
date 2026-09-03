package com.repoguard.agent.dto;

public record GithubChecksPreviewDto(
    boolean attempted,
    boolean created,
    String headSha,
    String externalId,
    Long remoteCheckRunId,
    String desiredStage,
    long desiredVersion,
    String appliedStage,
    long appliedVersion,
    int retryAttempts,
    int annotationCount,
    boolean annotationTruncated,
    String status,
    String conclusion,
    String message
) {

    public static GithubChecksPreviewDto notAttempted() {
        return new GithubChecksPreviewDto(
            false, false, null, null, null, "NOT_CREATED", 0, null, 0,
            0, 0, false, "NOT_ATTEMPTED", null, "尚未执行 Check Run 预览"
        );
    }
}
