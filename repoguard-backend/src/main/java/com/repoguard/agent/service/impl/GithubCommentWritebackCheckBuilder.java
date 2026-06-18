package com.repoguard.agent.service.impl;

import com.repoguard.agent.config.GithubIntegrationSettings;
import com.repoguard.agent.dto.GithubCommentWritebackCheck;
import com.repoguard.agent.entity.ReviewTask;
import java.util.List;
import org.springframework.util.StringUtils;

/**
 * Builds GitHub writeback preflight diagnostics for preview and publish flows.
 */
public class GithubCommentWritebackCheckBuilder {

    public GithubCommentWritebackCheck build(ReviewTask task, GithubIntegrationSettings settings) {
        GithubIntegrationSettings effectiveSettings = settings == null ? GithubIntegrationSettings.empty() : settings;
        String taskOwner = trimToNull(task.getOrganization());
        String taskRepository = trimToNull(task.getRepository());
        String configuredOwner = trimToNull(effectiveSettings.defaultOwner());
        String configuredRepository = trimToNull(effectiveSettings.defaultRepo());
        boolean tokenConfigured = StringUtils.hasText(effectiveSettings.token());
        boolean repositoryConfigured = StringUtils.hasText(configuredOwner) && StringUtils.hasText(configuredRepository);
        boolean repositoryMatched = repositoryConfigured
            && equalsIgnoreCase(taskOwner, configuredOwner)
            && equalsIgnoreCase(taskRepository, configuredRepository);
        boolean connectionHealthy = tokenConfigured && repositoryConfigured && repositoryMatched;

        String status = resolveStatus(tokenConfigured, repositoryConfigured, repositoryMatched, connectionHealthy);
        return new GithubCommentWritebackCheck(
            status,
            resolveLevel(status),
            taskOwner,
            taskRepository,
            configuredOwner,
            configuredRepository,
            repositoryMatched,
            tokenConfigured,
            connectionHealthy,
            effectiveSettings.lastError(),
            messages(effectiveSettings, tokenConfigured, repositoryConfigured, repositoryMatched)
        );
    }

    private List<String> messages(
        GithubIntegrationSettings settings,
        boolean tokenConfigured,
        boolean repositoryConfigured,
        boolean repositoryMatched
    ) {
        List<String> messages = new java.util.ArrayList<>();
        if (!tokenConfigured) {
            messages.add("GitHub Token 未配置，请先到集成配置页保存 Token。");
        }
        if (!repositoryConfigured) {
            messages.add("GitHub 默认 owner/repo 未配置，无法提前判断任务仓库是否匹配。");
        } else if (!repositoryMatched) {
            messages.add("当前任务仓库与 GitHub 集成默认仓库不一致，请确认 Token 对目标仓库有评论权限。");
        }
        if (StringUtils.hasText(settings.lastError())) {
            messages.add("GitHub 最近一次连接测试失败：" + settings.lastError());
        } else if (settings.exists() && !"CONFIGURED".equals(settings.status())) {
            messages.add("GitHub 当前连接状态不是已配置成功，请先到集成配置页测试连接。");
        }
        if (messages.isEmpty()) {
            messages.add("GitHub 回写配置与当前任务仓库匹配。");
        }
        return messages;
    }

    private String resolveStatus(
        boolean tokenConfigured,
        boolean repositoryConfigured,
        boolean repositoryMatched,
        boolean connectionHealthy
    ) {
        if (!tokenConfigured) {
            return "token_missing";
        }
        if (!repositoryConfigured) {
            return "repository_not_configured";
        }
        if (!repositoryMatched) {
            return "repository_mismatch";
        }
        if (!connectionHealthy) {
            return "connection_failed";
        }
        return "ready";
    }

    private String resolveLevel(String status) {
        return switch (status) {
            case "ready" -> "success";
            case "repository_mismatch", "repository_not_configured" -> "warning";
            default -> "danger";
        };
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private boolean equalsIgnoreCase(String first, String second) {
        return first != null && second != null && first.equalsIgnoreCase(second);
    }
}
