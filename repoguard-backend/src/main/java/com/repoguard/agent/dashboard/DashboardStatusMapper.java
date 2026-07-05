package com.repoguard.agent.dashboard;

import com.repoguard.agent.config.GithubIntegrationSettings;
import com.repoguard.agent.config.ReviewPolicySettings;
import com.repoguard.agent.review.ReviewTaskStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class DashboardStatusMapper {

    static final String HEALTH_NORMAL = "正常";
    static final String HEALTH_ABNORMAL = "异常";
    static final String HEALTH_NOT_CONFIGURED = "未接入";
    static final String HEALTH_DISABLED = "已禁用";

    public String rabbitMqHealth(Boolean open) {
        return Boolean.TRUE.equals(open) ? HEALTH_NORMAL : HEALTH_ABNORMAL;
    }

    public String githubHealth(GithubIntegrationSettings settings) {
        if (settings == null || !StringUtils.hasText(settings.token())) {
            return HEALTH_NOT_CONFIGURED;
        }
        return "FAILED".equalsIgnoreCase(settings.status()) ? HEALTH_ABNORMAL : HEALTH_NORMAL;
    }

    public String llmHealth(ReviewPolicySettings settings) {
        if (settings == null || !settings.exists()) {
            return HEALTH_NOT_CONFIGURED;
        }
        if (!settings.enabled()) {
            return HEALTH_DISABLED;
        }
        return settings.readyForLlmReview() ? HEALTH_NORMAL : HEALTH_NOT_CONFIGURED;
    }

    public String reviewTaskStatusText(String status) {
        return switch (ReviewTaskStatus.from(status)) {
            case COMPLETED -> "已完成";
            case REVIEWING -> "审查中";
            case FAILED -> "失败";
            case QUEUED -> "排队中";
            case PUBLISH_FAILED -> "发布失败";
            case EXECUTION_TIMEOUT -> "执行超时";
            case PENDING_HUMAN_REVIEW -> "待人工复核";
            case APPROVED -> "已通过";
            case CHANGES_REQUESTED -> "需修改";
            case REJECTED -> "已拒绝";
            case UNKNOWN -> status;
        };
    }
}
