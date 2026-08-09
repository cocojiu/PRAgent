package com.repoguard.agent.service.impl;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.cache.CacheEvictionService;
import com.repoguard.agent.dto.FindingFeedbackRequest;
import com.repoguard.agent.dto.FindingFeedbackResponse;
import com.repoguard.agent.entity.ReviewFinding;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.ReviewFindingMapper;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.review.FindingFeedbackStatus;
import com.repoguard.agent.review.ReviewFindingRiskRecalibrator;
import com.repoguard.agent.review.task.ReviewTaskTransitionStore;
import com.repoguard.agent.service.FindingFeedbackService;
import com.repoguard.agent.timeline.ReviewTimelineAppender;
import com.repoguard.agent.timeline.ReviewTimelineStatus;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class FindingFeedbackServiceImpl implements FindingFeedbackService {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ReviewTaskMapper reviewTaskMapper;
    private final ReviewFindingMapper reviewFindingMapper;
    private final ReviewTimelineAppender reviewTimelineAppender;
    private final CacheEvictionService cacheEvictionService;
    private final ReviewFindingRiskRecalibrator riskRecalibrator;
    private final ReviewTaskTransitionStore transitionStore;

    @Autowired
    public FindingFeedbackServiceImpl(
        ReviewTaskMapper reviewTaskMapper,
        ReviewFindingMapper reviewFindingMapper,
        ReviewTimelineAppender reviewTimelineAppender,
        CacheEvictionService cacheEvictionService,
        ReviewFindingRiskRecalibrator riskRecalibrator,
        ReviewTaskTransitionStore transitionStore
    ) {
        this.reviewTaskMapper = reviewTaskMapper;
        this.reviewFindingMapper = reviewFindingMapper;
        this.reviewTimelineAppender = reviewTimelineAppender;
        this.cacheEvictionService = Objects.requireNonNull(cacheEvictionService, "cacheEvictionService");
        this.riskRecalibrator = Objects.requireNonNull(riskRecalibrator, "riskRecalibrator");
        this.transitionStore = Objects.requireNonNull(transitionStore, "transitionStore");
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

        FindingFeedbackStatus status = normalizeFindingFeedbackStatus(request.status());
        LocalDateTime feedbackAt = LocalDateTime.now();
        finding.setFeedbackStatus(status.code());
        finding.setFeedbackNote(cleanNote(request.note()));
        finding.setFeedbackBy(cleanOperator(operator));
        finding.setFeedbackAt(feedbackAt);
        reviewFindingMapper.updateById(finding);
        ReviewFindingRiskRecalibrator.Outcome recalibrated = riskRecalibrator.recalculate(taskId);
        transitionStore.recalibrateAfterFindingFeedback(
            task,
            recalibrated.riskLevel(),
            recalibrated.humanReviewRequired()
        );
        reviewTimelineAppender.completeCurrentAndAppend(
            task.getId(),
            findingFeedbackTimelineLabel(finding, status),
            feedbackAt,
            ReviewTimelineStatus.DONE
        );
        evictDashboardFeedbackQuality(task);
        return findingFeedbackResponse(finding);
    }

    private FindingFeedbackResponse findingFeedbackResponse(ReviewFinding finding) {
        return new FindingFeedbackResponse(
            finding.getId(),
            finding.getTaskId(),
            FindingFeedbackStatus.fromFinding(finding).dtoCode(),
            finding.getFeedbackNote(),
            finding.getFeedbackBy(),
            formatDateTimeOrNull(finding.getFeedbackAt())
        );
    }

    private FindingFeedbackStatus normalizeFindingFeedbackStatus(String status) {
        try {
            return FindingFeedbackStatus.from(status);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Unsupported finding feedback status: " + status);
        }
    }

    private String findingFeedbackTimelineLabel(ReviewFinding finding, FindingFeedbackStatus status) {
        String file = StringUtils.hasText(finding.getFilePath()) ? finding.getFilePath() : "unknown file";
        return truncate("Finding feedback updated: " + status.dtoCode() + " for " + file);
    }

    private void evictDashboardFeedbackQuality(ReviewTask task) {
        cacheEvictionService.evictDashboardReviewActivity(task.getCreatedAt().toLocalDate());
        cacheEvictionService.evictReviewRules();
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

}
