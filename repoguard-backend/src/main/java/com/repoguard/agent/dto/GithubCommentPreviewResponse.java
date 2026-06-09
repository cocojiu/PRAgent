package com.repoguard.agent.dto;

import java.util.List;

public record GithubCommentPreviewResponse(
    Long taskId,
    Integer prNumber,
    String prUrl,
    GithubCommentWritebackCheck writebackCheck,
    Integer totalFindings,
    Integer commentableCount,
    Integer blockedCount,
    List<GithubCommentPreviewItem> items
) {
}
