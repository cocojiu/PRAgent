package com.repoguard.agent.review.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.external.ExternalHttpFailureDiagnostics;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ReviewFailureSummaryResolver {
    private static final ReviewFailureSummary NO_FAILURE = new ReviewFailureSummary(null, null, null);
    private final LegacyReviewFailureAdapter legacy = new LegacyReviewFailureAdapter();
    private final ObjectMapper objectMapper;

    public ReviewFailureSummaryResolver(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public ReviewFailureSummary resolve(ReviewTask task, List<String> timelineLabels) {
        if ("SUPERSEDED".equalsIgnoreCase(task.getStatus())) {
            return present("review_superseded", "");
        }
        if (!"FAILED".equals(task.getStatus())) return NO_FAILURE;
        if (!StringUtils.hasText(task.getFailureDetailsJson())) return legacy.resolve(timelineLabels);
        // A stale/unknown snapshot must never reuse an older attempt's timeline classification.
        return ReviewFailureDetails.parse(task.getFailureDetailsJson(), objectMapper)
            .filter(details -> Objects.equals(details.attemptId(), task.getCurrentAttemptId()))
            .map(details -> present(details.category(), details.retryAfter()))
            .orElseGet(() -> present("review_execution_failed", ""));
    }

    public record ReviewFailureSummary(String category, String reason, String suggestion) { }

    private static final Map<String, ReviewFailureSummary> POLICIES = Map.ofEntries(
        Map.entry("review_superseded", new ReviewFailureSummary("review_superseded", "PR Head 已变化，本次审查未执行", "请按最新提交重新发起审查。")),
        Map.entry("github_token_invalid", new ReviewFailureSummary("github_token_invalid", "GitHub Token 无效或已过期", "请到集成配置页更新 GitHub Token，确认保存成功后再重试审查。")),
        Map.entry("github_permission_denied", new ReviewFailureSummary("github_permission_denied", "GitHub Token 权限不足", "请确认 Token 对目标仓库和 PR 具备读取权限，必要时补充 repo 权限后重试。")),
        Map.entry("github_target_not_found", new ReviewFailureSummary("github_target_not_found", "PR 或仓库不存在/不可访问", "请确认仓库名称、组织、PR 编号和 Token 可访问范围，然后重新触发审查。")),
        Map.entry("github_rate_limited", new ReviewFailureSummary("github_rate_limited", "GitHub API 访问受限", "请稍后重试，或更换剩余额度充足的 GitHub Token。")),
        Map.entry("github_service_unavailable", new ReviewFailureSummary("github_service_unavailable", "GitHub API 暂时不可用", "请稍后重试，并关注 GitHub 服务状态或企业代理网络状态。")),
        Map.entry("github_timeout", new ReviewFailureSummary("github_timeout", "GitHub API 响应超时", "请检查网络、GitHub 服务状态和代理配置，稍后再重试审查。")),
        Map.entry("llm_auth_failed", new ReviewFailureSummary("llm_auth_failed", "LLM 鉴权失败", "请检查 LLM API Key、Provider 和 Base URL 配置，保存成功后再重试。")),
        Map.entry("llm_rate_limited", new ReviewFailureSummary("llm_rate_limited", "LLM 调用受限", "请稍后重试，或调整供应商额度、并发与限流配置。")),
        Map.entry("llm_service_unavailable", new ReviewFailureSummary("llm_service_unavailable", "LLM 服务暂时不可用", "请稍后重试，必要时切换模型或启用规则兜底。")),
        Map.entry("llm_timeout", new ReviewFailureSummary("llm_timeout", "LLM 响应超时", "请检查模型服务状态、网络和超时配置，稍后再重试。")),
        Map.entry("llm_request_invalid", new ReviewFailureSummary("llm_request_invalid", "LLM 请求配置无效", "请检查模型名称、Base URL、请求参数和供应商兼容性配置。")),
        Map.entry("github_resource_not_found", new ReviewFailureSummary("github_resource_not_found", "PR 或仓库不存在/不可访问", "请确认仓库名称、组织、PR 编号和 Token 可访问范围，然后重新触发审查。")),
        Map.entry("external_service_timeout", new ReviewFailureSummary("external_service_timeout", "外部服务响应超时", "请检查网络、GitHub 和 LLM 服务状态，稍后再重试审查。")),
        Map.entry("llm_result_parse_failed", new ReviewFailureSummary("llm_result_parse_failed", "LLM 输出解析失败", "请检查 LLM 模型返回格式或临时启用规则兜底后重试。")),
        Map.entry("llm_config_incomplete", new ReviewFailureSummary("llm_config_incomplete", "LLM 配置不完整", "请在系统配置中补全 LLM Provider、模型和密钥，保存后再重试。")),
        Map.entry("review_timeout", new ReviewFailureSummary("review_timeout", "外部服务响应超时", "请检查网络和外部服务状态，稍后再重试。")),
        Map.entry("review_external_rate_limited", new ReviewFailureSummary("review_external_rate_limited", "外部服务调用受限", "请稍后重试，或检查供应商额度与并发配置。")),
        Map.entry("review_external_auth_failed", new ReviewFailureSummary("review_external_auth_failed", "外部服务鉴权失败", "请检查集成凭据和访问权限。")),
        Map.entry("review_external_service_unavailable", new ReviewFailureSummary("review_external_service_unavailable", "外部服务暂时不可用", "请稍后重试并检查服务状态。")),
        Map.entry("review_external_request_failed", new ReviewFailureSummary("review_external_request_failed", "外部请求失败", "请检查请求参数、目标资源和集成配置。")),
        Map.entry("review_payload_invalid", new ReviewFailureSummary("review_payload_invalid", "审查数据格式无效", "请检查输入与模型输出格式，必要时启用规则兜底。")),
        Map.entry("review_configuration_invalid", new ReviewFailureSummary("review_configuration_invalid", "审查配置无效", "请检查集成和模型配置，修正后重试。")),
        Map.entry("review_state_conflict", new ReviewFailureSummary("review_state_conflict", "任务状态发生冲突", "请刷新任务，确认当前状态后再重试。")),
        Map.entry("review_database_error", new ReviewFailureSummary("review_database_error", "审查数据访问失败", "请检查数据库运行状态及诊断日志。")),
        Map.entry("review_tenant_inactive", new ReviewFailureSummary("review_tenant_inactive", "租户当前不可用", "请联系管理员确认租户状态。")),
        Map.entry("review_execution_failed", new ReviewFailureSummary("review_execution_failed", "审查执行失败", "请检查 GitHub/LLM 集成配置和任务时间线，修复后点击重试。"))
    );

    private static ReviewFailureSummary present(String category, String retryAfter) {
        if ("llm_model_or_endpoint_not_found".equals(category)) category = "llm_request_invalid";
        ReviewFailureSummary policy = POLICIES.getOrDefault(category, POLICIES.get("review_execution_failed"));
        if (("github_rate_limited".equals(category) || "llm_rate_limited".equals(category)
            || "review_external_rate_limited".equals(category))
            && retryAfter != null && !retryAfter.isBlank()) {
            return new ReviewFailureSummary(policy.category(), policy.reason(),
                "建议等待 " + retryAfter + " 后再重试。" + policy.suggestion());
        }
        return policy;
    }

    private static final class LegacyReviewFailureAdapter {
        ReviewFailureSummary resolve(List<String> timelineLabels) {
            String detail = timelineLabels.stream().filter(StringUtils::hasText)
                .filter(label -> label.equals("Review failed") || label.startsWith("Review failed:"))
                .reduce((first, second) -> second).map(this::extractFailureDetail).orElse("");
            return present(classifyFailure(detail),
                ExternalHttpFailureDiagnostics.fromDetail(detail).retryAfter());
        }
        private String extractFailureDetail(String label) {
            if (label.startsWith("Review failed:")) {
                return label.replaceFirst("Review failed:", "").trim();
            }
            return "";
        }

        private String classifyFailure(String detail) {
            String normalized = StringUtils.hasText(detail) ? detail.trim() : "";
            String lowerDetail = normalized.toLowerCase(Locale.ROOT);

            for (String category : List.of("github_token_invalid", "github_permission_denied", "github_target_not_found",
                "github_rate_limited", "github_service_unavailable", "github_timeout", "llm_auth_failed",
                "llm_rate_limited", "llm_service_unavailable", "llm_timeout", "llm_request_invalid")) {
                if (lowerDetail.contains("category=" + category)) return category;
            }
            if (lowerDetail.contains("category=llm_model_or_endpoint_not_found")) return "llm_request_invalid";
            // Ordered rules retain the historical precedence without coupling new failures to text.
            return List.of(
                Map.entry("github_token_invalid", List.of("401", "bad credentials", "unauthorized", "requires authentication")),
                Map.entry("github_permission_denied", List.of("403", "forbidden", "resource not accessible", "permission")),
                Map.entry("github_resource_not_found", List.of("404", "not found")),
                Map.entry("github_rate_limited", List.of("rate limit")),
                Map.entry("external_service_timeout", List.of("timeout", "timed out")),
                Map.entry("llm_result_parse_failed", List.of("unable to parse llm review result", "llm review result")),
                Map.entry("llm_config_incomplete", List.of("llm config is incomplete", "api key")))
                .stream().filter(rule -> rule.getValue().stream().anyMatch(lowerDetail::contains))
                .map(Map.Entry::getKey).findFirst().orElse("review_execution_failed");
        }

    }

    /** A bounded, non-diagnostic snapshot saved atomically with the terminal task state. */
    public record ReviewFailureDetails(String category, Boolean retryable, String stage, Long attemptId, String retryAfter) {
        public ReviewFailureDetails {
            category = category != null && category.matches("[a-z][a-z0-9_]{0,63}") ? category : "review_execution_failed";
            stage = stage != null && stage.matches("[A-Z][A-Z0-9_]{0,31}") ? stage : "REVIEW_EXECUTION";
            retryAfter = retryAfter == null ? "" : retryAfter.replaceAll("[^A-Za-z0-9,: GMT+_.-]", "");
            retryAfter = retryAfter.substring(0, Math.min(64, retryAfter.length()));
        }

        public String toJson(ObjectMapper objectMapper) {
            try {
                return objectMapper.writeValueAsString(this);
            } catch (JsonProcessingException ex) {
                throw new IllegalStateException("Unable to serialize failure snapshot", ex);
            }
        }

        public static Optional<ReviewFailureDetails> parse(String json, ObjectMapper objectMapper) {
            if (json == null || json.isBlank() || json.length() > 2048) return Optional.empty();
            try {
                return Optional.ofNullable(objectMapper.readValue(json, ReviewFailureDetails.class));
            } catch (JsonProcessingException ex) {
                return Optional.empty();
            }
        }
    }

}
