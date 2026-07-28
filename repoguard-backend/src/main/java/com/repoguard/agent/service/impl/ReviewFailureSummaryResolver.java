package com.repoguard.agent.service.impl;

import com.repoguard.agent.external.ExternalRetryAfterHint;
import com.repoguard.agent.entity.ReviewTask;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ReviewFailureSummaryResolver {

    private static final ReviewFailureSummary NO_FAILURE_SUMMARY = new ReviewFailureSummary(null, null, null);

    public ReviewFailureSummary resolve(ReviewTask task, List<String> timelineLabels) {
        if ("SUPERSEDED".equalsIgnoreCase(task.getStatus())) {
            return new ReviewFailureSummary(
                "review_superseded",
                "PR Head 已变化，本次审查未执行",
                "请按最新提交重新发起审查。"
            );
        }
        if (!"FAILED".equals(task.getStatus())) {
            return NO_FAILURE_SUMMARY;
        }

        String detail = timelineLabels.stream()
            .filter(StringUtils::hasText)
            .filter(label -> label.equals("Review failed") || label.startsWith("Review failed:"))
            .reduce((first, second) -> second)
            .map(this::extractFailureDetail)
            .orElse("");
        return classifyFailure(detail);
    }

    private String extractFailureDetail(String label) {
        if (label.startsWith("Review failed:")) {
            return label.replaceFirst("Review failed:", "").trim();
        }
        return "";
    }

    private ReviewFailureSummary classifyFailure(String detail) {
        String normalized = StringUtils.hasText(detail) ? detail.trim() : "";
        String lowerDetail = normalized.toLowerCase(Locale.ROOT);

        if (lowerDetail.contains("category=github_token_invalid")) {
            return new ReviewFailureSummary(
                "github_token_invalid",
                "GitHub Token 无效或已过期",
                "请到集成配置页更新 GitHub Token，确认保存成功后再重试审查。"
            );
        }
        if (lowerDetail.contains("category=github_permission_denied")) {
            return new ReviewFailureSummary(
                "github_permission_denied",
                "GitHub Token 权限不足",
                "请确认 Token 对目标仓库和 PR 具备读取权限，必要时补充 repo 权限后重试。"
            );
        }
        if (lowerDetail.contains("category=github_target_not_found")) {
            return new ReviewFailureSummary(
                "github_target_not_found",
                "PR 或仓库不存在/不可访问",
                "请确认仓库名称、组织、PR 编号和 Token 可访问范围，然后重新触发审查。"
            );
        }
        if (lowerDetail.contains("category=github_rate_limited")) {
            return new ReviewFailureSummary(
                "github_rate_limited",
                "GitHub API 访问受限",
                rateLimitSuggestion(detail, "请稍后重试，或更换剩余额度充足的 GitHub Token。")
            );
        }
        if (lowerDetail.contains("category=github_service_unavailable")) {
            return new ReviewFailureSummary(
                "github_service_unavailable",
                "GitHub API 暂时不可用",
                "请稍后重试，并关注 GitHub 服务状态或企业代理网络状态。"
            );
        }
        if (lowerDetail.contains("category=github_timeout")) {
            return new ReviewFailureSummary(
                "github_timeout",
                "GitHub API 响应超时",
                "请检查网络、GitHub 服务状态和代理配置，稍后再重试审查。"
            );
        }
        if (lowerDetail.contains("category=llm_auth_failed")) {
            return new ReviewFailureSummary(
                "llm_auth_failed",
                "LLM 鉴权失败",
                "请检查 LLM API Key、Provider 和 Base URL 配置，保存成功后再重试。"
            );
        }
        if (lowerDetail.contains("category=llm_rate_limited")) {
            return new ReviewFailureSummary(
                "llm_rate_limited",
                "LLM 调用受限",
                rateLimitSuggestion(detail, "请稍后重试，或调整供应商额度、并发与限流配置。")
            );
        }
        if (lowerDetail.contains("category=llm_service_unavailable")) {
            return new ReviewFailureSummary(
                "llm_service_unavailable",
                "LLM 服务暂时不可用",
                "请稍后重试，必要时切换模型或启用规则兜底。"
            );
        }
        if (lowerDetail.contains("category=llm_timeout")) {
            return new ReviewFailureSummary(
                "llm_timeout",
                "LLM 响应超时",
                "请检查模型服务状态、网络和超时配置，稍后再重试。"
            );
        }
        if (lowerDetail.contains("category=llm_request_invalid")
            || lowerDetail.contains("category=llm_model_or_endpoint_not_found")) {
            return new ReviewFailureSummary(
                "llm_request_invalid",
                "LLM 请求配置无效",
                "请检查模型名称、Base URL、请求参数和供应商兼容性配置。"
            );
        }
        if (lowerDetail.contains("401")
            || lowerDetail.contains("bad credentials")
            || lowerDetail.contains("unauthorized")
            || lowerDetail.contains("requires authentication")) {
            return new ReviewFailureSummary(
                "github_token_invalid",
                "GitHub Token 无效或已过期",
                "请到集成配置页更新 GitHub Token，确认保存成功后再重试审查。"
            );
        }
        if (lowerDetail.contains("403")
            || lowerDetail.contains("forbidden")
            || lowerDetail.contains("resource not accessible")
            || lowerDetail.contains("permission")) {
            return new ReviewFailureSummary(
                "github_permission_denied",
                "GitHub Token 权限不足",
                "请确认 Token 对目标仓库和 PR 具备读取权限，必要时补充 repo 权限后重试。"
            );
        }
        if (lowerDetail.contains("404") || lowerDetail.contains("not found")) {
            return new ReviewFailureSummary(
                "github_resource_not_found",
                "PR 或仓库不存在/不可访问",
                "请确认仓库名称、组织、PR 编号和 Token 可访问范围，然后重新触发审查。"
            );
        }
        if (lowerDetail.contains("rate limit")) {
            return new ReviewFailureSummary(
                "github_rate_limited",
                "GitHub API 访问受限",
                rateLimitSuggestion(detail, "请稍后重试，或更换剩余额度充足的 GitHub Token。")
            );
        }
        if (lowerDetail.contains("timeout") || lowerDetail.contains("timed out")) {
            return new ReviewFailureSummary(
                "external_service_timeout",
                "外部服务响应超时",
                "请检查网络、GitHub 和 LLM 服务状态，稍后再重试审查。"
            );
        }
        if (lowerDetail.contains("unable to parse llm review result") || lowerDetail.contains("llm review result")) {
            return new ReviewFailureSummary(
                "llm_result_parse_failed",
                "LLM 输出解析失败",
                "请检查 LLM 模型返回格式或临时启用规则兜底后重试。"
            );
        }
        if (lowerDetail.contains("llm config is incomplete") || lowerDetail.contains("api key")) {
            return new ReviewFailureSummary(
                "llm_config_incomplete",
                "LLM 配置不完整",
                "请在系统配置中补全 LLM Provider、模型和密钥，保存后再重试。"
            );
        }
        return new ReviewFailureSummary(
            "review_execution_failed",
            "审查执行失败",
            "请检查 GitHub/LLM 集成配置和任务时间线，修复后点击重试。"
        );
    }

    public record ReviewFailureSummary(String category, String reason, String suggestion) {
    }

    private String rateLimitSuggestion(String detail, String fallback) {
        return ExternalRetryAfterHint.suggestionSuffix(detail) + fallback;
    }
}
