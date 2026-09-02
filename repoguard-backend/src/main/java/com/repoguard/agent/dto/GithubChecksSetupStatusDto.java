package com.repoguard.agent.dto;

import java.util.List;

public record GithubChecksSetupStatusDto(
    String organization,
    String repository,
    boolean appEnabled,
    boolean appConfigured,
    Long installationId,
    boolean installationAllowlisted,
    boolean repositoryAuthorized,
    boolean metadataPermission,
    boolean contentsPermission,
    boolean pullRequestsPermission,
    boolean checksPermission,
    boolean globalCheckRunEnabled,
    boolean repositoryCheckRunEnabled,
    boolean effectiveCheckRunEnabled,
    long policyVersion,
    GithubChecksWebhookStatusDto webhook,
    List<GithubChecksDiagnosticDto> diagnostics,
    GithubChecksPreviewDto preview,
    boolean ready,
    String mergeGateGuidance
) {
    public GithubChecksSetupStatusDto {
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        preview = preview == null ? GithubChecksPreviewDto.notAttempted() : preview;
    }
}
