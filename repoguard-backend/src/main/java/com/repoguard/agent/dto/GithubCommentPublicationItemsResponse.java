package com.repoguard.agent.dto;

import java.util.List;

/** A stable, bounded page of items within one tenant-owned publication batch. */
public record GithubCommentPublicationItemsResponse(
    Long taskId,
    Long batchId,
    long total,
    int pageSize,
    long afterId,
    Long nextAfterId,
    boolean hasMore,
    List<GithubCommentPublicationHistoryItem> items
) {
}
