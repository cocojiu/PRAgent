package com.repoguard.agent.dto;

import java.util.List;

/**
 * GitHub 评论回写前的配置校验提示。
 *
 * <p>它只负责提前暴露仓库和 Token 风险，不直接替代真实 GitHub API 调用结果。
 */
public record GithubCommentWritebackCheck(
    String status,
    String level,
    String taskOwner,
    String taskRepository,
    String configuredOwner,
    String configuredRepository,
    Boolean repositoryMatched,
    Boolean tokenConfigured,
    Boolean connectionHealthy,
    String lastError,
    List<String> messages
) {
}
