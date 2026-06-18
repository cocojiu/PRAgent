package com.repoguard.agent.service;

import com.repoguard.agent.dto.GithubCommentPublicationHistoryResponse;

public interface GithubCommentHistoryQueryService {

    GithubCommentPublicationHistoryResponse getPublicationHistory(
        Long taskId,
        int page,
        int pageSize,
        String status
    );
}
