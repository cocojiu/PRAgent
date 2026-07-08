package com.repoguard.agent.dto;

import java.util.List;

public record GithubCommentPublishResponse(
    Long taskId,
    Long batchId,
    String status,
    Integer totalFindings,
    Integer attemptedCount,
    Integer succeededCount,
    Integer failedCount,
    Integer skippedCount,
    List<GithubCommentPublishItem> items
) {
    public GithubCommentPublishResponse(
        Long taskId,
        Integer totalFindings,
        Integer attemptedCount,
        Integer succeededCount,
        Integer failedCount,
        Integer skippedCount,
        List<GithubCommentPublishItem> items
    ) {
        this(taskId, null, null, totalFindings, attemptedCount, succeededCount, failedCount, skippedCount, items);
    }

    public static GithubCommentPublishResponse queued(Long taskId, Long batchId, Integer totalFindings) {
        return new GithubCommentPublishResponse(
            taskId,
            batchId,
            "queued",
            totalFindings,
            0,
            0,
            0,
            0,
            List.of()
        );
    }
}
