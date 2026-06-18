package com.repoguard.agent.service;

import com.repoguard.agent.dto.GithubCommentPublishResponse;

public interface GithubCommentPublishService {

    GithubCommentPublishResponse publishGithubComments(Long taskId);
}
