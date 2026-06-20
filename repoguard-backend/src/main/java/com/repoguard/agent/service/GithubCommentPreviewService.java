package com.repoguard.agent.service;

import com.repoguard.agent.dto.GithubCommentPreviewResponse;

public interface GithubCommentPreviewService {

    GithubCommentPreviewResponse getPreview(Long taskId);
}
