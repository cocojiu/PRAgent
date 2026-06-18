package com.repoguard.agent.github;

import java.util.Locale;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class GithubWritebackFailureClassifier {

    private static final FailureSummary NO_FAILURE = new FailureSummary(null, null, null);

    public FailureSummary classify(String status, Boolean success, String message) {
        if (Boolean.TRUE.equals(success) || !"failed".equalsIgnoreCase(status)) {
            return NO_FAILURE;
        }

        String normalized = StringUtils.hasText(message) ? message.trim() : "";
        String lowerMessage = normalized.toLowerCase(Locale.ROOT);

        if (lowerMessage.contains("category=github_token_invalid")) {
            return failure(
                "github_token_invalid",
                "GitHub Token 无效或已过期",
                "请到集成配置页更新 GitHub Token，确认连接测试通过后重新回写。"
            );
        }
        if (lowerMessage.contains("category=github_permission_denied")) {
            return failure(
                "github_permission_denied",
                "GitHub Token 权限不足",
                "请确认 Token 对目标仓库具备 Pull Request/Issue 评论权限后重新回写。"
            );
        }
        if (lowerMessage.contains("category=github_target_not_found")) {
            return failure(
                "github_target_not_found",
                "GitHub PR 或仓库不可访问",
                "请确认任务仓库、PR 编号和 Token 可访问范围，再重新回写评论。"
            );
        }
        if (lowerMessage.contains("category=github_rate_limited")) {
            return failure(
                "github_rate_limited",
                "GitHub API 访问受限",
                "请稍后重试，或更换剩余额度充足的 GitHub Token。"
            );
        }
        if (lowerMessage.contains("category=github_timeout")) {
            return failure(
                "github_writeback_timeout",
                "GitHub 回写请求超时",
                "请检查网络和 GitHub 服务状态，稍后重新回写。"
            );
        }
        if (lowerMessage.contains("category=github_service_unavailable")) {
            return failure(
                "github_service_unavailable",
                "GitHub API 暂时不可用",
                "请稍后重试，并关注 GitHub 服务状态或企业代理网络状态。"
            );
        }
        if (lowerMessage.contains("token is not configured")) {
            return failure(
                "github_token_missing",
                "GitHub Token 未配置",
                "请到集成配置页保存 GitHub Token 后重新回写评论。"
            );
        }
        if (lowerMessage.contains("401") || lowerMessage.contains("bad credentials")
            || lowerMessage.contains("unauthorized") || lowerMessage.contains("requires authentication")) {
            return failure(
                "github_token_invalid",
                "GitHub Token 无效或已过期",
                "请到集成配置页更新 GitHub Token，确认连接测试通过后重新回写。"
            );
        }
        if (lowerMessage.contains("403") || lowerMessage.contains("forbidden")
            || lowerMessage.contains("resource not accessible") || lowerMessage.contains("permission")) {
            return failure(
                "github_permission_denied",
                "GitHub Token 权限不足",
                "请确认 Token 对目标仓库具备 Pull Request/Issue 评论权限后重新回写。"
            );
        }
        if (lowerMessage.contains("404") || lowerMessage.contains("not found")) {
            return failure(
                "github_target_not_found",
                "GitHub PR 或仓库不可访问",
                "请确认任务仓库、PR 编号和 Token 可访问范围，再重新回写评论。"
            );
        }
        if (isCommentPositionFailure(lowerMessage)) {
            return failure(
                "github_comment_position_invalid",
                "GitHub 行评论定位失败",
                "请检查该审查发现是否仍在 PR Diff 中；必要时改为 PR 总评评论。"
            );
        }
        if (lowerMessage.contains("rate limit")) {
            return failure(
                "github_rate_limited",
                "GitHub API 访问受限",
                "请稍后重试，或更换剩余额度充足的 GitHub Token。"
            );
        }
        if (lowerMessage.contains("timeout") || lowerMessage.contains("timed out")) {
            return failure(
                "github_writeback_timeout",
                "GitHub 回写请求超时",
                "请检查网络和 GitHub 服务状态，稍后重新回写。"
            );
        }
        if (lowerMessage.contains("owner or repository is not configured")) {
            return failure(
                "github_repository_not_configured",
                "GitHub 仓库未配置",
                "请在集成配置中补全默认仓库，或确认任务携带了正确仓库信息。"
            );
        }
        return failure(
            "github_writeback_failed",
            "GitHub 评论回写失败",
            "请查看原始错误信息，确认 GitHub 集成配置和目标 PR 状态后重试。"
        );
    }

    private FailureSummary failure(String category, String reason, String suggestion) {
        return new FailureSummary(category, reason, suggestion);
    }

    private boolean isCommentPositionFailure(String lowerMessage) {
        return lowerMessage.contains("422")
            || lowerMessage.contains("validation failed")
            || lowerMessage.contains("position")
            || lowerMessage.contains("commit_id")
            || lowerMessage.contains("line must")
            || lowerMessage.contains("line is")
            || lowerMessage.contains("line does not")
            || lowerMessage.contains("not part of the diff")
            || lowerMessage.contains("diff hunk");
    }

    public record FailureSummary(String category, String reason, String suggestion) {
    }
}
