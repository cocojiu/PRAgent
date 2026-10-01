package com.repoguard.agent.notification.center;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.repoguard.agent.github.GithubIntegrationProvider;
import com.repoguard.agent.github.GithubIntegrationSettings;
import com.repoguard.agent.config.EnterpriseEditionEnabled;
import com.repoguard.agent.review.ReviewPolicyProvider;
import com.repoguard.agent.review.ReviewPolicySettings;
import com.repoguard.agent.dto.NotificationCenterDto;
import com.repoguard.agent.dto.NotificationItemDto;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.messaging.RabbitRuntimeHealthProbe;
import com.repoguard.agent.review.LlmStatus;
import com.repoguard.agent.review.AssessmentStatus;
import com.repoguard.agent.review.ReviewTaskStatus;
import com.repoguard.agent.service.NotificationService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@EnterpriseEditionEnabled
public class NotificationServiceImpl implements NotificationService {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_NOTIFICATIONS = 12;

    private final ReviewTaskMapper reviewTaskMapper;
    private final GithubIntegrationProvider githubIntegrationProvider;
    private final ReviewPolicyProvider reviewPolicyProvider;
    private final RabbitRuntimeHealthProbe rabbitRuntimeHealthProbe;

    public NotificationServiceImpl(
        ReviewTaskMapper reviewTaskMapper,
        GithubIntegrationProvider githubIntegrationProvider,
        ReviewPolicyProvider reviewPolicyProvider,
        RabbitRuntimeHealthProbe rabbitRuntimeHealthProbe
    ) {
        this.reviewTaskMapper = reviewTaskMapper;
        this.githubIntegrationProvider = githubIntegrationProvider;
        this.reviewPolicyProvider = reviewPolicyProvider;
        this.rabbitRuntimeHealthProbe = rabbitRuntimeHealthProbe;
    }

    @Override
    public NotificationCenterDto getNotifications() {
        LocalDateTime now = LocalDateTime.now();
        Set<Long> notifiedTasks = new HashSet<>();
        List<NotificationItemDto> items = new ArrayList<>();
        addOverdueHumanReviewNotifications(items, safeTasks(new LambdaQueryWrapper<ReviewTask>()
            .eq(ReviewTask::getStatus, ReviewTaskStatus.PENDING_HUMAN_REVIEW.code())
            .eq(ReviewTask::getHumanReviewStatus, "PENDING")
            .isNotNull(ReviewTask::getReviewSlaDeadline).le(ReviewTask::getReviewSlaDeadline, now)
            .orderByAsc(ReviewTask::getReviewSlaDeadline, ReviewTask::getId), 4, notifiedTasks), notifiedTasks);
        addIntegrationNotifications(items);
        List<NotificationItemDto> failed = new ArrayList<>();
        addFailedTaskNotifications(failed, safeTasks(new LambdaQueryWrapper<ReviewTask>()
            .eq(ReviewTask::getStatus, ReviewTaskStatus.FAILED.code())
            .orderByDesc(ReviewTask::getCreatedAt, ReviewTask::getId), 4, notifiedTasks), notifiedTasks);
        List<NotificationItemDto> highRisk = new ArrayList<>();
        addHighRiskNotifications(highRisk, safeTasks(unresolvedAssessmentQuery()
            .eq(ReviewTask::getAssessmentStatus, AssessmentStatus.COMPLETE.name())
            .in(ReviewTask::getRiskLevel, "HIGH", "CRITICAL")
            .orderByDesc(ReviewTask::getCreatedAt, ReviewTask::getId), 4, notifiedTasks), notifiedTasks);
        List<NotificationItemDto> fallback = new ArrayList<>();
        addFallbackNotifications(fallback, safeTasks(unresolvedAssessmentQuery()
            .eq(ReviewTask::getLlmStatus, LlmStatus.FALLBACK.name())
            .orderByDesc(ReviewTask::getCreatedAt, ReviewTask::getId), 3, notifiedTasks), notifiedTasks);
        // Reserve overdue/system slots, then give each remaining category a turn.
        List<List<NotificationItemDto>> groups = List.of(failed, highRisk, fallback);
        for (int offset = 0; offset < 4 && items.size() < MAX_NOTIFICATIONS; offset++) {
            for (List<NotificationItemDto> group : groups) {
                if (offset < group.size() && items.size() < MAX_NOTIFICATIONS) items.add(group.get(offset));
            }
        }
        return new NotificationCenterDto(items.size(), format(now), List.copyOf(items));
    }

    private LambdaQueryWrapper<ReviewTask> unresolvedAssessmentQuery() {
        return new LambdaQueryWrapper<ReviewTask>()
            .in(ReviewTask::getStatus, ReviewTaskStatus.COMPLETED.code(), ReviewTaskStatus.PENDING_HUMAN_REVIEW.code())
            .in(ReviewTask::getHumanReviewStatus, "PENDING", "NOT_REQUIRED")
            .isNull(ReviewTask::getHumanReviewedAt);
    }

    private List<ReviewTask> safeTasks(LambdaQueryWrapper<ReviewTask> query, int limit, Set<Long> notifiedTasks) {
        List<ReviewTask> tasks = reviewTaskMapper.selectList(query
            .select(ReviewTask::getId, ReviewTask::getPrNumber, ReviewTask::getTitle, ReviewTask::getRepository,
                ReviewTask::getStatus, ReviewTask::getAssessmentStatus, ReviewTask::getRiskLevel, ReviewTask::getLlmStatus,
                ReviewTask::getHumanReviewStatus, ReviewTask::getReviewSlaDeadline, ReviewTask::getReviewAssignee, ReviewTask::getCreatedAt)
            .notIn(!notifiedTasks.isEmpty(), ReviewTask::getId, List.copyOf(notifiedTasks))
            .last("limit " + limit));
        return tasks == null ? List.of() : tasks;
    }

    private void addFailedTaskNotifications(List<NotificationItemDto> items, List<ReviewTask> tasks, Set<Long> notifiedTasks) {
        tasks.stream()
            .filter(task -> notifiedTasks.add(task.getId()))
            .limit(4)
            .map(task -> taskNotification(
                "review-failed-" + task.getId(),
                "danger",
                "审查任务失败",
                taskTitle(task) + " 执行失败，建议查看失败原因并重试。",
                task
            ))
            .forEach(items::add);
    }

    private void addHighRiskNotifications(List<NotificationItemDto> items, List<ReviewTask> tasks, Set<Long> notifiedTasks) {
        tasks.stream()
            .filter(task -> notifiedTasks.add(task.getId()))
            .limit(4)
            .map(task -> taskNotification(
                "review-high-risk-" + task.getId(),
                "danger",
                "高风险 PR 待处理",
                taskTitle(task) + " 当前风险等级为 " + riskText(task.getRiskLevel()) + "。",
                task
            ))
            .forEach(items::add);
    }

    private void addFallbackNotifications(List<NotificationItemDto> items, List<ReviewTask> tasks, Set<Long> notifiedTasks) {
        tasks.stream()
            .filter(task -> notifiedTasks.add(task.getId()))
            .limit(3)
            .map(task -> taskNotification(
                "review-llm-fallback-" + task.getId(),
                "warning",
                "LLM 审查已降级",
                taskTitle(task) + " 已使用规则兜底结果。",
                task
            ))
            .forEach(items::add);
    }

    private void addOverdueHumanReviewNotifications(List<NotificationItemDto> items, List<ReviewTask> tasks, Set<Long> notifiedTasks) {
        tasks.stream()
            .filter(task -> notifiedTasks.add(task.getId()))
            .limit(4)
            .map(task -> taskNotification(
                "review-sla-overdue-" + task.getId(),
                "warning",
                "人工复核已超时",
                taskTitle(task) + (StringUtils.hasText(task.getReviewAssignee()) ? "（负责人：" + task.getReviewAssignee() + "）" : "") + " 已超过 SLA，需要升级处理。",
                task
            ))
            .forEach(items::add);
    }

    private void addIntegrationNotifications(List<NotificationItemDto> items) {
        addGithubNotification(items);
        addRabbitMqNotification(items);
        addLlmNotification(items);
    }

    private void addGithubNotification(List<NotificationItemDto> items) {
        try {
            GithubIntegrationSettings settings = githubIntegrationProvider.getSettings();
            if (!StringUtils.hasText(settings.token())) {
                items.add(systemNotification(
                    "integration-github-missing",
                    "warning",
                    "GitHub Token 未配置",
                    "无法读取 PR 或回写评论，请前往集成配置补充 Token。",
                    "/repoguard/integrations"
                ));
                return;
            }
            if ("FAILED".equalsIgnoreCase(settings.status())) {
                items.add(systemNotification(
                    "integration-github-failed",
                    "danger",
                    "GitHub 连接异常",
                    StringUtils.hasText(settings.lastError()) ? settings.lastError() : "最近一次 GitHub 连接测试失败。",
                    "/repoguard/integrations"
                ));
            }
        } catch (RuntimeException ex) {
            items.add(systemNotification(
                "integration-github-check-failed",
                "danger",
                "GitHub 状态检查失败",
                conciseError(ex),
                "/repoguard/integrations"
            ));
        }
    }

    private void addRabbitMqNotification(List<NotificationItemDto> items) {
        try {
            if (!"CONNECTED".equals(rabbitRuntimeHealthProbe.connectionStatus())) {
                items.add(systemNotification(
                    "integration-rabbitmq-failed",
                    "danger",
                    "RabbitMQ 连接异常",
                    "消息队列通道不可用，审查任务可能无法消费。",
                    "/repoguard/integrations"
                ));
            }
        } catch (RuntimeException ex) {
            items.add(systemNotification(
                "integration-rabbitmq-check-failed",
                "danger",
                "RabbitMQ 状态检查失败",
                conciseError(ex),
                "/repoguard/integrations"
            ));
        }
    }

    private void addLlmNotification(List<NotificationItemDto> items) {
        try {
            ReviewPolicySettings settings = reviewPolicyProvider.getSettings();
            if (!settings.exists() || !settings.enabled()) {
                items.add(systemNotification(
                    "llm-disabled",
                    "warning",
                    "LLM 审查未启用",
                    "当前会依赖规则兜底结果，建议检查审查策略配置。",
                    "/repoguard/settings"
                ));
                return;
            }
            if (!settings.readyForLlmReview()) {
                items.add(systemNotification(
                    "llm-missing-secret",
                    "warning",
                    "LLM API Key 未配置",
                    "AI 审查可能无法执行，请前往集成配置补充 API Key。",
                    "/repoguard/integrations"
                ));
            }
        } catch (RuntimeException ex) {
            items.add(systemNotification(
                "llm-check-failed",
                "danger",
                "LLM 状态检查失败",
                conciseError(ex),
                "/repoguard/integrations"
            ));
        }
    }

    private NotificationItemDto taskNotification(String id, String level, String title, String description, ReviewTask task) {
        LocalDateTime createdAt = task.getCreatedAt() == null ? LocalDateTime.now() : task.getCreatedAt();
        return new NotificationItemDto(
            id,
            level,
            title,
            description,
            relativeTime(createdAt),
            "/repoguard/tasks/" + task.getId(),
            format(createdAt)
        );
    }

    private NotificationItemDto systemNotification(String id, String level, String title, String description, String targetPath) {
        LocalDateTime now = LocalDateTime.now();
        return new NotificationItemDto(id, level, title, description, "刚刚", targetPath, format(now));
    }

    private String taskTitle(ReviewTask task) {
        return task.getRepository() + " PR #" + task.getPrNumber() + "：" + task.getTitle();
    }

    private String riskText(String riskLevel) {
        if ("CRITICAL".equalsIgnoreCase(riskLevel)) {
            return "严重风险";
        }
        if ("HIGH".equalsIgnoreCase(riskLevel)) {
            return "高风险";
        }
        return lower(riskLevel);
    }

    private String relativeTime(LocalDateTime time) {
        Duration duration = Duration.between(time, LocalDateTime.now());
        if (duration.isNegative() || duration.toMinutes() < 1) {
            return "刚刚";
        }
        if (duration.toMinutes() < 60) {
            return duration.toMinutes() + " 分钟前";
        }
        if (duration.toHours() < 24) {
            return duration.toHours() + " 小时前";
        }
        return duration.toDays() + " 天前";
    }

    private String conciseError(RuntimeException ex) {
        String message = ex.getMessage();
        if (!StringUtils.hasText(message) && ex.getCause() != null) {
            message = ex.getCause().getMessage();
        }
        if (!StringUtils.hasText(message)) {
            return ex.getClass().getSimpleName();
        }
        String normalized = message.replaceAll("\\s+", " ").trim();
        return normalized.length() > 120 ? normalized.substring(0, 117) + "..." : normalized;
    }

    private String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private String format(LocalDateTime time) {
        return time == null ? null : time.format(DATE_TIME_FORMATTER);
    }
}
