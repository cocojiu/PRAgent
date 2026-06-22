package com.repoguard.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.config.CacheEvictionService;
import com.repoguard.agent.dto.HumanReviewRequest;
import com.repoguard.agent.dto.HumanReviewResponse;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.entity.ReviewTimeline;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.mapper.ReviewTimelineMapper;
import com.repoguard.agent.review.HumanReviewStatus;
import com.repoguard.agent.review.ReviewTaskStateMachine;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class HumanReviewCommandService {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ReviewTaskMapper reviewTaskMapper;
    private final ReviewTimelineMapper reviewTimelineMapper;
    private final ReviewTaskStateMachine reviewTaskStateMachine;
    private final CacheEvictionService cacheEvictionService;

    public HumanReviewCommandService(
        ReviewTaskMapper reviewTaskMapper,
        ReviewTimelineMapper reviewTimelineMapper,
        ReviewTaskStateMachine reviewTaskStateMachine,
        CacheEvictionService cacheEvictionService
    ) {
        this.reviewTaskMapper = reviewTaskMapper;
        this.reviewTimelineMapper = reviewTimelineMapper;
        this.reviewTaskStateMachine = reviewTaskStateMachine == null
            ? new ReviewTaskStateMachine()
            : reviewTaskStateMachine;
        this.cacheEvictionService = cacheEvictionService;
    }

    public HumanReviewResponse submit(Long id, HumanReviewRequest request, String operator) {
        ReviewTask task = reviewTaskMapper.selectById(id);
        if (task == null) {
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND, "Review task not found: " + id);
        }
        reviewTaskStateMachine.ensureHumanReviewAllowed(
            Boolean.TRUE.equals(task.getHumanReviewRequired()),
            resolveHumanReviewStatus(task)
        );

        String action = normalizeHumanReviewAction(request.action());
        LocalDateTime reviewedAt = LocalDateTime.now();
        String note = cleanHumanReviewNote(request.note());
        String humanReviewStatus = humanReviewStatusForAction(action);
        task.setStatus(taskStatusForHumanReview(humanReviewStatus));
        task.setHumanReviewStatus(humanReviewStatus);
        task.setHumanReviewNote(note);
        task.setHumanReviewBy(cleanOperator(operator));
        task.setHumanReviewedAt(reviewedAt);
        reviewTaskMapper.updateById(task);
        appendReviewTimeline(task.getId(), humanReviewTimelineLabel(humanReviewStatus, note), reviewedAt, "DONE");
        evictDashboardOverview();
        return humanReviewResponse(task, humanReviewMessage(humanReviewStatus));
    }

    private void appendReviewTimeline(Long taskId, String label, LocalDateTime eventTime, String status) {
        reviewTimelineMapper.update(
            new UpdateWrapper<ReviewTimeline>()
                .eq("task_id", taskId)
                .eq("status", "CURRENT")
                .set("status", "DONE")
        );

        ReviewTimeline timeline = new ReviewTimeline();
        timeline.setTaskId(taskId);
        timeline.setLabel(label);
        timeline.setEventTime(eventTime);
        timeline.setStatus(status);
        timeline.setSortOrder(nextTimelineSortOrder(taskId));
        reviewTimelineMapper.insert(timeline);
    }

    private int nextTimelineSortOrder(Long taskId) {
        ReviewTimeline latest = reviewTimelineMapper.selectOne(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ReviewTimeline>()
                .eq(ReviewTimeline::getTaskId, taskId)
                .orderByDesc(ReviewTimeline::getSortOrder)
                .last("limit 1")
        );
        return latest == null || latest.getSortOrder() == null ? 1 : latest.getSortOrder() + 1;
    }

    private HumanReviewResponse humanReviewResponse(ReviewTask task, String message) {
        return new HumanReviewResponse(
            task.getId(),
            lower(task.getStatus()),
            Boolean.TRUE.equals(task.getHumanReviewRequired()),
            lower(resolveHumanReviewStatus(task)),
            task.getHumanReviewNote(),
            task.getHumanReviewBy(),
            formatDateTimeOrNull(task.getHumanReviewedAt()),
            message
        );
    }

    private String normalizeHumanReviewAction(String action) {
        return action == null ? "" : action.trim().toUpperCase(Locale.ROOT);
    }

    private String cleanHumanReviewNote(String note) {
        return StringUtils.hasText(note) ? note.trim() : null;
    }

    private String cleanOperator(String operator) {
        return StringUtils.hasText(operator) ? truncate(operator.trim()) : "unknown";
    }

    private String humanReviewStatusForAction(String action) {
        HumanReviewStatus humanReviewStatus = HumanReviewStatus.fromAction(action);
        if (humanReviewStatus == HumanReviewStatus.UNKNOWN) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Unsupported human review action: " + action);
        }
        return humanReviewStatus.code();
    }

    private String taskStatusForHumanReview(String humanReviewStatus) {
        return reviewTaskStateMachine.statusAfterHumanReview(humanReviewStatus);
    }

    private String resolveHumanReviewStatus(ReviewTask task) {
        if (StringUtils.hasText(task.getHumanReviewStatus())) {
            return HumanReviewStatus.from(task.getHumanReviewStatus()).code();
        }
        return HumanReviewStatus.defaultForRequired(Boolean.TRUE.equals(task.getHumanReviewRequired())).code();
    }

    private String humanReviewTimelineLabel(String humanReviewStatus, String note) {
        String base = switch (HumanReviewStatus.from(humanReviewStatus)) {
            case APPROVED -> "Human review approved";
            case CHANGES_REQUESTED -> "Human review requested changes";
            case REJECTED -> "Human review rejected";
            default -> "Human review updated";
        };
        return StringUtils.hasText(note) ? truncate(base + ": " + note) : base;
    }

    private String humanReviewMessage(String humanReviewStatus) {
        return switch (HumanReviewStatus.from(humanReviewStatus)) {
            case APPROVED -> "Human review approved";
            case CHANGES_REQUESTED -> "Human review requested changes";
            case REJECTED -> "Human review rejected";
            default -> "Human review updated";
        };
    }

    private String formatDateTimeOrNull(LocalDateTime value) {
        return value == null ? null : value.format(DATE_TIME_FORMATTER);
    }

    private void evictDashboardOverview() {
        if (cacheEvictionService != null) {
            cacheEvictionService.evictDashboardOverview();
        }
    }

    private String truncate(String value) {
        return value.length() > 120 ? value.substring(0, 117) + "..." : value;
    }

    private String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }
}
