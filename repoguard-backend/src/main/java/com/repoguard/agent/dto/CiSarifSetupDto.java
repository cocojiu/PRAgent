package com.repoguard.agent.dto;

import java.util.List;

/** Read-only CI setup metadata. Never contains upload credentials or SARIF content. */
public record CiSarifSetupDto(
    Long taskId, Long attemptId, String organization, String repository, Integer prNumber,
    String commitSha, long credentialTtlSeconds, int maxUploadBytes, int maxSarifBytes,
    String sarifVersion, List<Upload> recentUploads
) {
    public record Upload(
        Long batchId, String toolName, String toolVersion, String scanRunId,
        String status, int imported, int skipped, String completedAt
    ) { }
}
