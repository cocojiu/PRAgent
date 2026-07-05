package com.repoguard.agent.service;

import com.repoguard.agent.dto.GithubCommentPublicationHistoryResponse;
import com.repoguard.agent.dto.GithubCommentPreviewResponse;
import com.repoguard.agent.dto.GithubCommentPublishResponse;

public interface GithubCommentApplicationService {

    GithubCommentPreviewResponse getGithubCommentPreview(Long taskId);

    GithubCommentPreviewResponse getGithubCommentPreview(Long taskId, int page, int pageSize, boolean commentableOnly);

    GithubCommentPublishResponse publishGithubComments(Long taskId);

    default GithubCommentPublicationHistoryResponse getGithubCommentPublicationHistory(Long taskId) {
        return getGithubCommentPublicationHistory(taskId, 1, 20, null);
    }

    GithubCommentPublicationHistoryResponse getGithubCommentPublicationHistory(Long taskId, int page, int pageSize, String status);
}
