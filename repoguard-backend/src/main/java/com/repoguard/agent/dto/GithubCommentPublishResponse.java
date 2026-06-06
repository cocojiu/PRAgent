package com.repoguard.agent.dto;

import java.util.List;

public record GithubCommentPublishResponse(
    Long taskId,
    Integer totalFindings,
    Integer attemptedCount,
    Integer succeededCount,
    Integer failedCount,
    Integer skippedCount,
    List<GithubCommentPublishItem> items
) {
}
