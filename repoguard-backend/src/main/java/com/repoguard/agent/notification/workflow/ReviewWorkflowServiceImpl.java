package com.repoguard.agent.notification.workflow;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.config.EnterpriseEditionEnabled;
import com.repoguard.agent.config.ReviewWorkflowProperties;
import com.repoguard.agent.dto.NotificationReadRequest;
import com.repoguard.agent.dto.NotificationReportDto;
import com.repoguard.agent.dto.PageResponse;
import com.repoguard.agent.dto.ReviewAssignmentRequest;
import com.repoguard.agent.dto.ReviewBotCommandRequest;
import com.repoguard.agent.dto.ReviewBotCommandResponse;
import com.repoguard.agent.dto.ReviewEscalationResponse;
import com.repoguard.agent.dto.ReviewWorkflowItemDto;
import com.repoguard.agent.entity.NotificationReadState;
import com.repoguard.agent.entity.ReviewBotCommandAudit;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.NotificationReadStateMapper;
import com.repoguard.agent.mapper.ReviewBotCommandAuditMapper;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.review.ReviewTaskStatus;
import com.repoguard.agent.service.ReviewTaskCommandService;
import com.repoguard.agent.service.ReviewWorkflowService;
import com.repoguard.agent.review.task.ReviewTaskTransitionStore;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@EnterpriseEditionEnabled
public class ReviewWorkflowServiceImpl implements ReviewWorkflowService {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String PENDING_HUMAN_REVIEW = "PENDING_HUMAN_REVIEW";
    private static final List<String> BOT_PROVIDERS = List.of("GITHUB", "GITLAB", "GITEE", "BITBUCKET");

    private final ReviewTaskMapper reviewTaskMapper;
    private final NotificationReadStateMapper readStateMapper;
    private final ReviewBotCommandAuditMapper botAuditMapper;
    private final ReviewTaskCommandService reviewTaskCommandService;
    private final ReviewTaskTransitionStore transitionStore;
    private final ReviewWorkflowProperties properties;

    public ReviewWorkflowServiceImpl(
        ReviewTaskMapper reviewTaskMapper,
        NotificationReadStateMapper readStateMapper,
        ReviewBotCommandAuditMapper botAuditMapper,
        ReviewTaskCommandService reviewTaskCommandService,
        ReviewTaskTransitionStore transitionStore,
        ReviewWorkflowProperties properties
    ) {
        this.reviewTaskMapper = reviewTaskMapper;
        this.readStateMapper = readStateMapper;
        this.botAuditMapper = botAuditMapper;
        this.reviewTaskCommandService = reviewTaskCommandService;
        this.transitionStore = transitionStore;
        this.properties = properties;
    }

    @Override
    public PageResponse<ReviewWorkflowItemDto> listQueue(int page, int pageSize, String assignee, Boolean overdue) {
        LocalDateTime now = LocalDateTime.now();
        QueryWrapper<ReviewTask> query = new QueryWrapper<ReviewTask>()
            .eq("status", PENDING_HUMAN_REVIEW)
            .eq(StringUtils.hasText(assignee), "review_assignee", clean(assignee))
            .orderByAsc("review_sla_deadline")
            .orderByDesc("created_at");
        if (Boolean.TRUE.equals(overdue)) {
            query.isNotNull("review_sla_deadline").le("review_sla_deadline", now);
        }
        Page<ReviewTask> result = reviewTaskMapper.selectPage(Page.of(page, pageSize), query);
        return new PageResponse<>(result.getRecords().stream().map(task -> toItem(task, now)).toList(), result.getTotal());
    }

    @Override
    @Transactional
    public ReviewWorkflowItemDto assign(Long taskId, ReviewAssignmentRequest request, String operator) {
        ReviewTask task = requireTask(taskId);
        if (!PENDING_HUMAN_REVIEW.equalsIgnoreCase(task.getStatus())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Only pending human review tasks can be assigned");
        }
        String assignee = StringUtils.hasText(request == null ? null : request.assignee()) ? clean(request.assignee()) : null;
        int slaMinutes = request != null && request.slaMinutes() != null
            ? request.slaMinutes() : properties.getHumanReviewSlaMinutes();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime deadline = assignee == null ? null : now.plusMinutes(slaMinutes);
        if (!transitionStore.assignHumanReview(task, assignee, assignee == null ? null : now, deadline)) {
            throw new BusinessException(ErrorCode.CONFLICT, "Review task changed, please refresh");
        }
        task.setReviewAssignee(assignee);
        task.setReviewAssignedAt(assignee == null ? null : now);
        task.setReviewSlaDeadline(deadline);
        return toItem(task, now);
    }

    @Override
    @Transactional
    public ReviewEscalationResponse escalateOverdue() {
        LocalDateTime now = LocalDateTime.now();
        List<ReviewTask> tasks = reviewTaskMapper.selectList(new QueryWrapper<ReviewTask>()
            .eq("status", PENDING_HUMAN_REVIEW)
            .isNotNull("review_sla_deadline")
            .le("review_sla_deadline", now)
            .orderByAsc("review_sla_deadline")
            .last("limit 100"));
        int escalated = 0;
        int skipped = 0;
        for (ReviewTask task : tasks) {
            int level = task.getReviewEscalationLevel() == null ? 0 : task.getReviewEscalationLevel();
            if (level >= properties.getEscalationLimit()) {
                skipped++;
                continue;
            }
            if (transitionStore.escalateHumanReview(task, level, now)) {
                task.setReviewEscalationLevel(level + 1);
                task.setReviewLastEscalatedAt(now);
                escalated++;
            } else {
                skipped++;
            }
        }
        return new ReviewEscalationResponse(escalated, skipped, format(now));
    }

    @Override
    @Transactional
    public ReviewBotCommandResponse executeBotCommand(String provider, ReviewBotCommandRequest request, String actor) {
        String normalizedProvider = clean(provider).toUpperCase(Locale.ROOT);
        if (!BOT_PROVIDERS.contains(normalizedProvider)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Unsupported bot provider: " + provider);
        }
        ReviewBotCommandAudit previous = botAuditMapper.selectOne(new QueryWrapper<ReviewBotCommandAudit>()
            .eq("provider", normalizedProvider)
            .eq("external_command_id", request.externalCommandId()));
        if (previous != null) {
            return new ReviewBotCommandResponse(normalizedProvider, commandName(previous.getCommandText()), previous.getStatus(), previous.getTaskId(), previous.getResponseMessage());
        }
        String actorKey = StringUtils.hasText(actor) ? clean(actor) : "bot";
        ParsedCommand parsed = parse(request.text(), request.taskId());
        ReviewBotCommandResponse response = executeParsed(normalizedProvider, parsed, actorKey);
        ReviewBotCommandAudit audit = new ReviewBotCommandAudit();
        audit.setProvider(normalizedProvider);
        audit.setExternalCommandId(request.externalCommandId().trim());
        audit.setCommandText(request.text().trim());
        audit.setActorKey(actorKey);
        audit.setTaskId(response.taskId());
        audit.setStatus(response.status());
        audit.setResponseMessage(response.message());
        audit.setCreatedAt(LocalDateTime.now());
        botAuditMapper.insert(audit);
        return response;
    }

    @Override
    @Transactional
    public void markNotificationRead(NotificationReadRequest request, String readerKey) {
        String reader = StringUtils.hasText(readerKey) ? clean(readerKey) : "anonymous";
        String key = clean(request.notificationKey());
        LocalDateTime now = LocalDateTime.now();
        NotificationReadState state = readStateMapper.selectOne(new QueryWrapper<NotificationReadState>()
            .eq("reader_key", reader).eq("notification_key", key));
        if (state == null) {
            state = new NotificationReadState();
            state.setReaderKey(reader);
            state.setNotificationKey(key);
            state.setReadAt(now);
            state.setCreatedAt(now);
            state.setUpdatedAt(now);
            readStateMapper.insert(state);
        } else {
            state.setReadAt(now);
            state.setUpdatedAt(now);
            readStateMapper.updateById(state);
        }
    }

    @Override
    public List<String> listReadNotificationKeys(String readerKey) {
        return readStateMapper.selectList(new QueryWrapper<NotificationReadState>()
            .eq("reader_key", StringUtils.hasText(readerKey) ? clean(readerKey) : "anonymous")
            .orderByDesc("read_at").last("limit 500"))
            .stream().map(NotificationReadState::getNotificationKey).toList();
    }

    @Override
    public NotificationReportDto report(String period) {
        String normalized = clean(period).toUpperCase(Locale.ROOT);
        int days = "WEEKLY".equals(normalized) ? 7 : 1;
        if (!List.of("DAILY", "WEEKLY").contains(normalized)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "period must be DAILY or WEEKLY");
        }
        LocalDateTime to = LocalDateTime.now();
        LocalDateTime from = to.minusDays(days);
        List<ReviewTask> tasks = reviewTaskMapper.selectList(new QueryWrapper<ReviewTask>().ge("created_at", from));
        long completed = tasks.stream().filter(task -> "COMPLETED".equalsIgnoreCase(task.getStatus()) || "APPROVED".equalsIgnoreCase(task.getStatus())).count();
        long failed = tasks.stream().filter(task -> "FAILED".equalsIgnoreCase(task.getStatus())).count();
        long pending = tasks.stream().filter(task -> PENDING_HUMAN_REVIEW.equalsIgnoreCase(task.getStatus())).count();
        long highRisk = tasks.stream().filter(task -> "HIGH".equalsIgnoreCase(task.getRiskLevel()) || "CRITICAL".equalsIgnoreCase(task.getRiskLevel())).count();
        long overdue = tasks.stream().filter(task -> PENDING_HUMAN_REVIEW.equalsIgnoreCase(task.getStatus()) && task.getReviewSlaDeadline() != null && !task.getReviewSlaDeadline().isAfter(to)).count();
        return new NotificationReportDto(normalized, format(from), format(to), tasks.size(), completed, failed, pending, highRisk, overdue);
    }

    private ReviewBotCommandResponse executeParsed(String provider, ParsedCommand parsed, String actor) {
        if (parsed.taskId() == null) {
            return new ReviewBotCommandResponse(provider, parsed.command(), "REJECTED", null, "命令需要提供 taskId");
        }
        return switch (parsed.command()) {
            case "review" -> {
                try {
                    reviewTaskCommandService.retryReview(parsed.taskId());
                    yield new ReviewBotCommandResponse(provider, "review", "ACCEPTED", parsed.taskId(), "已重新排队审查");
                } catch (RuntimeException ex) {
                    yield new ReviewBotCommandResponse(provider, "review", "REJECTED", parsed.taskId(), concise(ex));
                }
            }
            case "assign" -> {
                if (!StringUtils.hasText(parsed.assignee())) {
                    yield new ReviewBotCommandResponse(provider, "assign", "REJECTED", parsed.taskId(), "assign 命令需要负责人");
                }
                try {
                    assign(parsed.taskId(), new ReviewAssignmentRequest(parsed.assignee(), null), actor);
                    yield new ReviewBotCommandResponse(provider, "assign", "ACCEPTED", parsed.taskId(), "已分派给 " + parsed.assignee());
                } catch (RuntimeException ex) {
                    yield new ReviewBotCommandResponse(provider, "assign", "REJECTED", parsed.taskId(), concise(ex));
                }
            }
            case "status" -> {
                ReviewTask task = reviewTaskMapper.selectById(parsed.taskId());
                yield task == null
                    ? new ReviewBotCommandResponse(provider, "status", "REJECTED", parsed.taskId(), "审查任务不存在")
                    : new ReviewBotCommandResponse(provider, "status", "ACCEPTED", parsed.taskId(), "当前状态：" + task.getStatus());
            }
            default -> new ReviewBotCommandResponse(provider, parsed.command(), "REJECTED", parsed.taskId(), "不支持的命令，请使用 review、assign 或 status");
        };
    }

    private ParsedCommand parse(String text, Long requestTaskId) {
        String[] parts = text.trim().split("\\s+");
        if (parts.length < 2 || !"/repoguard".equalsIgnoreCase(parts[0])) {
            return new ParsedCommand("unknown", requestTaskId, null);
        }
        String command = parts[1].toLowerCase(Locale.ROOT);
        Long taskId = requestTaskId;
        if (taskId == null && parts.length > 2) {
            try { taskId = Long.valueOf(parts[2]); } catch (NumberFormatException ignored) { }
        }
        String assignee = "assign".equals(command) && parts.length > 3 ? parts[3] : null;
        return new ParsedCommand(command, taskId, assignee);
    }

    private ReviewTask requireTask(Long taskId) {
        ReviewTask task = reviewTaskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND, "Review task not found: " + taskId);
        }
        return task;
    }

    private ReviewWorkflowItemDto toItem(ReviewTask task, LocalDateTime now) {
        LocalDateTime deadline = task.getReviewSlaDeadline();
        return new ReviewWorkflowItemDto(task.getId(), task.getRepository(), task.getPrNumber(), task.getTitle(), task.getStatus(), task.getHumanReviewStatus(), task.getReviewAssignee(), format(task.getReviewAssignedAt()), format(deadline), task.getReviewEscalationLevel() == null ? 0 : task.getReviewEscalationLevel(), deadline != null && !deadline.isAfter(now));
    }

    private String commandName(String text) {
        if (!StringUtils.hasText(text)) return "unknown";
        String[] parts = text.trim().split("\\s+");
        return parts.length > 1 ? parts[1].toLowerCase(Locale.ROOT) : "unknown";
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private String concise(RuntimeException ex) {
        String message = ex.getMessage();
        return StringUtils.hasText(message) ? message.length() > 240 ? message.substring(0, 237) + "..." : message : ex.getClass().getSimpleName();
    }

    private String format(LocalDateTime value) { return value == null ? null : value.format(FORMATTER); }

    private record ParsedCommand(String command, Long taskId, String assignee) { }
}
