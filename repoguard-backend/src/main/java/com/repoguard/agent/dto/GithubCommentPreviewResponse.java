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
    Integer publishedCount,
    Integer itemTotal,
    Integer page,
    Integer pageSize,
    Boolean commentableOnly,
    List<GithubCommentPreviewItem> items
) {
    public GithubCommentPreviewResponse(
        Long taskId,
        Integer prNumber,
        String prUrl,
        GithubCommentWritebackCheck writebackCheck,
        Integer totalFindings,
        Integer commentableCount,
        Integer blockedCount,
        List<GithubCommentPreviewItem> items
    ) {
        this(
            taskId,
            prNumber,
            prUrl,
            writebackCheck,
            totalFindings,
            commentableCount,
            blockedCount,
            items == null ? 0 : (int) items.stream().filter(item -> Boolean.TRUE.equals(item.published())).count(),
            items == null ? 0 : items.size(),
            1,
            items == null ? 0 : items.size(),
            false,
            items
        );
    }
}
