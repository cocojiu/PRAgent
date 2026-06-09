package com.repoguard.agent.dto;

import java.util.List;

/**
 * 单次 GitHub 评论回写操作的前端展示模型。
 */
public record GithubCommentPublicationBatchDto(
    Long batchId,
    String status,
    Integer totalFindings,
    Integer attemptedCount,
    Integer succeededCount,
    Integer failedCount,
    Integer skippedCount,
    String createdAt,
    String completedAt,
    List<GithubCommentPublicationHistoryItem> items
) {
}
