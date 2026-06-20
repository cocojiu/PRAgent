package com.repoguard.agent.service.impl;

import com.repoguard.agent.dto.ReviewTaskListItem;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.service.impl.ReviewFailureSummaryResolver.ReviewFailureSummary;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ReviewTaskListItemAssembler {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String SOURCE_MANUAL_INPUT = "MANUAL_INPUT";
    private static final String HUMAN_REVIEW_PENDING = "PENDING";
    private static final String HUMAN_REVIEW_NOT_REQUIRED = "NOT_REQUIRED";

    public ReviewTaskListItem assemble(ReviewTask task, ReviewFailureSummary failureSummary) {
        return new ReviewTaskListItem(
            task.getId(),
            task.getPrNumber(),
            task.getTitle(),
            task.getRepository(),
            task.getOrganization(),
            task.getCommitSha(),
            task.getBranchName(),
            lower(task.getStatus()),
            lower(task.getRiskLevel()),
            task.getMqRetries(),
            lower(task.getLlmStatus()),
            lower(resolveStoredSource(task.getSource())),
            lower(resolveStoredSource(task.getTriggerSource())),
            task.getCreatedAt().format(DATE_TIME_FORMATTER),
            formatDuration(task.getDurationSeconds()),
            failureSummary.category(),
            failureSummary.reason(),
            failureSummary.suggestion(),
            Boolean.TRUE.equals(task.getHumanReviewRequired()),
            lower(resolveHumanReviewStatus(task)),
            task.getHumanReviewNote(),
            task.getHumanReviewBy(),
            formatDateTimeOrNull(task.getHumanReviewedAt())
        );
    }

    private String resolveStoredSource(String source) {
        return StringUtils.hasText(source) ? source : SOURCE_MANUAL_INPUT;
    }

    private String resolveHumanReviewStatus(ReviewTask task) {
        if (!Boolean.TRUE.equals(task.getHumanReviewRequired())) {
            return HUMAN_REVIEW_NOT_REQUIRED;
        }
        return StringUtils.hasText(task.getHumanReviewStatus()) ? task.getHumanReviewStatus() : HUMAN_REVIEW_PENDING;
    }

    private String formatDateTimeOrNull(LocalDateTime value) {
        return value == null ? null : value.format(DATE_TIME_FORMATTER);
    }

    private String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    private String formatDuration(Integer durationSeconds) {
        int totalSeconds = durationSeconds == null ? 0 : durationSeconds;
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        return minutes + " 分 " + seconds + " 秒";
    }
}
