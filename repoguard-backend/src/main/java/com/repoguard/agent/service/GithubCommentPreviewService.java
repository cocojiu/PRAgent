package com.repoguard.agent.service;

import com.repoguard.agent.dto.GithubCommentPreviewResponse;

public interface GithubCommentPreviewService {

    GithubCommentPreviewResponse getPreview(Long taskId);

    GithubCommentPreviewResponse getPreview(Long taskId, int page, int pageSize, boolean commentableOnly);

    GithubCommentPreviewResponse getFullPreview(Long taskId);
}
