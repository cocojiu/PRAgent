package com.repoguard.agent.service;

import com.repoguard.agent.dto.GithubCommentPublicationHistoryResponse;
import com.repoguard.agent.dto.GithubCommentPublicationItemsResponse;

public interface GithubCommentHistoryQueryService {

    GithubCommentPublicationHistoryResponse getPublicationHistory(
        Long taskId,
        int page,
        int pageSize,
        String status
    );

    GithubCommentPublicationHistoryResponse getPublicationBatches(Long taskId, int page, int pageSize, String status);

    GithubCommentPublicationItemsResponse getPublicationItems(Long taskId, Long batchId, long afterId, int pageSize);
}
