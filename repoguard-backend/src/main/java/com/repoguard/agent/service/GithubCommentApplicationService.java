package com.repoguard.agent.service;

import com.repoguard.agent.dto.GithubCommentPublicationHistoryResponse;

public interface GithubCommentApplicationService {

    default GithubCommentPublicationHistoryResponse getGithubCommentPublicationHistory(Long taskId) {
        return getGithubCommentPublicationHistory(taskId, 1, 20, null);
    }

    GithubCommentPublicationHistoryResponse getGithubCommentPublicationHistory(Long taskId, int page, int pageSize, String status);
}
