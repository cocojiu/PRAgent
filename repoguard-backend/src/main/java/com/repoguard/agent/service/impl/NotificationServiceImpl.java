package com.repoguard.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.repoguard.agent.github.GithubIntegrationProvider;
import com.repoguard.agent.github.GithubIntegrationSettings;
import com.repoguard.agent.review.ReviewPolicyProvider;
import com.repoguard.agent.review.ReviewPolicySettings;
import com.repoguard.agent.dto.NotificationCenterDto;
import com.repoguard.agent.dto.NotificationItemDto;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.messaging.RabbitRuntimeHealthProbe;
import com.repoguard.agent.review.LlmStatus;
import com.repoguard.agent.review.ReviewTaskStatus;
import com.repoguard.agent.service.NotificationService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
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
        List<ReviewTask> tasks = safeTasks();
        List<NotificationItemDto> items = new ArrayList<>();
        addFailedTaskNotifications(items, tasks);
        addHighRiskNotifications(items, tasks);
        addFallbackNotifications(items, tasks);
        addIntegrationNotifications(items);

        List<NotificationItemDto> sortedItems = items.stream()
            .sorted(Comparator.comparing(NotificationItemDto::createdAt, Comparator.nullsLast(Comparator.reverseOrder())))
            .limit(MAX_NOTIFICATIONS)
            .toList();
        return new NotificationCenterDto(sortedItems.size(), format(LocalDateTime.now()), sortedItems);
    }

    private List<ReviewTask> safeTasks() {
        List<ReviewTask> tasks = reviewTaskMapper.selectList(
            new LambdaQueryWrapper<ReviewTask>()
                .orderByDesc(ReviewTask::getCreatedAt)
                .last("limit 50")
        );
        return tasks == null ? List.of() : tasks;
    }

    private void addFailedTaskNotifications(List<NotificationItemDto> items, List<ReviewTask> tasks) {
        tasks.stream()
            .filter(task -> ReviewTaskStatus.FAILED == ReviewTaskStatus.from(task.getStatus()))
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

    private void addHighRiskNotifications(List<NotificationItemDto> items, List<ReviewTask> tasks) {
        tasks.stream()
            .filter(task -> isHighRisk(task.getRiskLevel()))
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

    private void addFallbackNotifications(List<NotificationItemDto> items, List<ReviewTask> tasks) {
        tasks.stream()
            .filter(task -> LlmStatus.FALLBACK == LlmStatus.from(task.getLlmStatus()))
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

    private boolean isHighRisk(String riskLevel) {
        return "HIGH".equalsIgnoreCase(riskLevel) || "CRITICAL".equalsIgnoreCase(riskLevel);
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
