package com.repoguard.agent.service.impl;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.config.CacheEvictionService;
import com.repoguard.agent.dto.HumanReviewRequest;
import com.repoguard.agent.dto.HumanReviewResponse;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.review.HumanReviewStatus;
import com.repoguard.agent.review.ReviewTaskStateMachine;
import com.repoguard.agent.timeline.ReviewTimelineAppender;
import com.repoguard.agent.timeline.ReviewTimelineStatus;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class HumanReviewCommandService {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ReviewTaskMapper reviewTaskMapper;
    private final ReviewTimelineAppender reviewTimelineAppender;
    private final ReviewTaskStateMachine reviewTaskStateMachine;
    private final CacheEvictionService cacheEvictionService;

    @Autowired
    public HumanReviewCommandService(
        ReviewTaskMapper reviewTaskMapper,
        ReviewTimelineAppender reviewTimelineAppender,
        ReviewTaskStateMachine reviewTaskStateMachine,
        CacheEvictionService cacheEvictionService
    ) {
        this.reviewTaskMapper = reviewTaskMapper;
        this.reviewTimelineAppender = reviewTimelineAppender;
        this.reviewTaskStateMachine = Objects.requireNonNull(reviewTaskStateMachine, "reviewTaskStateMachine");
        this.cacheEvictionService = Objects.requireNonNull(cacheEvictionService, "cacheEvictionService");
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
        reviewTimelineAppender.completeCurrentAndAppend(
            task.getId(),
            humanReviewTimelineLabel(humanReviewStatus, note),
            reviewedAt,
            ReviewTimelineStatus.DONE
        );
        evictDashboardReviewActivity();
        return humanReviewResponse(task, humanReviewMessage(humanReviewStatus));
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

    private void evictDashboardReviewActivity() {
        cacheEvictionService.evictDashboardReviewActivity();
    }

    private String truncate(String value) {
        return value.length() > 120 ? value.substring(0, 117) + "..." : value;
    }

    private String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }
}
