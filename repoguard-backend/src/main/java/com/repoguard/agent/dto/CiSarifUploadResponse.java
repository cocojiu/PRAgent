package com.repoguard.agent.dto;

import java.time.OffsetDateTime;

public record CiSarifUploadResponse(
    Long taskId,
    Long attemptId,
    String toolName,
    String toolVersion,
    String scanRunId,
    String commitSha,
    String sarifFingerprint,
    OffsetDateTime completedAt,
    String status,
    int imported,
    int skipped
) {
}
