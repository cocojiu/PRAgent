package com.repoguard.agent.service.impl;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.config.CacheEvictionService;
import com.repoguard.agent.dto.FindingFeedbackRequest;
import com.repoguard.agent.dto.FindingFeedbackResponse;
import com.repoguard.agent.entity.ReviewFinding;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.ReviewFindingMapper;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.service.FindingFeedbackService;
import com.repoguard.agent.timeline.ReviewTimelineAppender;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class FindingFeedbackServiceImpl implements FindingFeedbackService {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String FEEDBACK_UNREVIEWED = "UNREVIEWED";
    private static final String FEEDBACK_VALID = "VALID";
    private static final String FEEDBACK_FALSE_POSITIVE = "FALSE_POSITIVE";
    private static final String FEEDBACK_FIXED = "FIXED";
    private static final String FEEDBACK_IGNORED = "IGNORED";

    private final ReviewTaskMapper reviewTaskMapper;
    private final ReviewFindingMapper reviewFindingMapper;
    private final ReviewTimelineAppender reviewTimelineAppender;
    private final CacheEvictionService cacheEvictionService;

    @Autowired
    public FindingFeedbackServiceImpl(
        ReviewTaskMapper reviewTaskMapper,
        ReviewFindingMapper reviewFindingMapper,
        ReviewTimelineAppender reviewTimelineAppender,
        CacheEvictionService cacheEvictionService
    ) {
        this.reviewTaskMapper = reviewTaskMapper;
        this.reviewFindingMapper = reviewFindingMapper;
        this.reviewTimelineAppender = reviewTimelineAppender;
        this.cacheEvictionService = Objects.requireNonNull(cacheEvictionService, "cacheEvictionService");
    }

    @Override
    @Transactional
    public FindingFeedbackResponse updateFindingFeedback(
        Long taskId,
        Long findingId,
        FindingFeedbackRequest request,
        String operator
    ) {
        ReviewTask task = reviewTaskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND, "Review task not found: " + taskId);
        }
        ReviewFinding finding = reviewFindingMapper.selectById(findingId);
        if (finding == null || !taskId.equals(finding.getTaskId()) || !"FINDING".equals(finding.getCategory())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Review finding not found: " + findingId);
        }

        String status = normalizeFindingFeedbackStatus(request.status());
        LocalDateTime feedbackAt = LocalDateTime.now();
        finding.setFeedbackStatus(status);
        finding.setFeedbackNote(cleanNote(request.note()));
        finding.setFeedbackBy(cleanOperator(operator));
        finding.setFeedbackAt(feedbackAt);
        reviewFindingMapper.updateById(finding);
        reviewTimelineAppender.completeCurrentAndAppend(
            task.getId(),
            findingFeedbackTimelineLabel(finding, status),
            feedbackAt,
            "DONE"
        );
        evictDashboardOverview();
        return findingFeedbackResponse(finding);
    }

    private FindingFeedbackResponse findingFeedbackResponse(ReviewFinding finding) {
        return new FindingFeedbackResponse(
            finding.getId(),
            finding.getTaskId(),
            lower(resolveFindingFeedbackStatus(finding)),
            finding.getFeedbackNote(),
            finding.getFeedbackBy(),
            formatDateTimeOrNull(finding.getFeedbackAt())
        );
    }

    private String normalizeFindingFeedbackStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return FEEDBACK_UNREVIEWED;
        }
        return switch (status.trim().toUpperCase(Locale.ROOT)) {
            case "VALID" -> FEEDBACK_VALID;
            case "FALSE_POSITIVE" -> FEEDBACK_FALSE_POSITIVE;
            case "FIXED" -> FEEDBACK_FIXED;
            case "IGNORED" -> FEEDBACK_IGNORED;
            case "UNREVIEWED" -> FEEDBACK_UNREVIEWED;
            default -> throw new BusinessException(ErrorCode.BAD_REQUEST, "Unsupported finding feedback status: " + status);
        };
    }

    private String resolveFindingFeedbackStatus(ReviewFinding finding) {
        return StringUtils.hasText(finding.getFeedbackStatus()) ? finding.getFeedbackStatus() : FEEDBACK_UNREVIEWED;
    }

    private String findingFeedbackTimelineLabel(ReviewFinding finding, String status) {
        String file = StringUtils.hasText(finding.getFilePath()) ? finding.getFilePath() : "unknown file";
        return truncate("Finding feedback updated: " + lower(status) + " for " + file);
    }

    private void evictDashboardOverview() {
        cacheEvictionService.evictDashboardOverview();
    }

    private String cleanNote(String note) {
        return StringUtils.hasText(note) ? note.trim() : null;
    }

    private String cleanOperator(String operator) {
        return StringUtils.hasText(operator) ? truncate(operator.trim()) : "unknown";
    }

    private String formatDateTimeOrNull(LocalDateTime value) {
        return value == null ? null : value.format(DATE_TIME_FORMATTER);
    }

    private String truncate(String value) {
        return value.length() > 120 ? value.substring(0, 117) + "..." : value;
    }

    private String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }
}
