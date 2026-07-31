package com.repoguard.agent.review.task;

import com.repoguard.agent.dto.ReviewTaskListItem;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.review.HumanReviewStatus;
import com.repoguard.agent.review.LlmStatus;
import com.repoguard.agent.review.ReviewTaskSource;
import com.repoguard.agent.review.task.ReviewFailureSummaryResolver.ReviewFailureSummary;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class ReviewTaskListItemAssembler {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

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
            lower(resolveLlmStatus(task)),
            ReviewTaskSource.dtoCodeOrDefault(task.getSource()),
            ReviewTaskSource.dtoCodeOrDefault(task.getTriggerSource()),
            task.getCreatedAt().format(DATE_TIME_FORMATTER),
            formatDuration(task.getDurationSeconds()),
            failureSummary.category(),
            failureSummary.reason(),
            failureSummary.suggestion(),
            Boolean.TRUE.equals(task.getHumanReviewRequired()),
            lower(resolveHumanReviewStatus(task)),
            task.getHumanReviewNote(),
            task.getHumanReviewBy(),
            formatDateTimeOrNull(task.getHumanReviewedAt()),
            lower(task.getAssessmentStatus())
        );
    }

    private String resolveLlmStatus(ReviewTask task) {
        if (task.getLlmStatus() == null || task.getLlmStatus().isBlank()) {
            return null;
        }
        return LlmStatus.from(task.getLlmStatus()).code();
    }

    private String resolveHumanReviewStatus(ReviewTask task) {
        if (task.getHumanReviewStatus() != null && !task.getHumanReviewStatus().isBlank()) {
            return HumanReviewStatus.from(task.getHumanReviewStatus()).code();
        }
        return HumanReviewStatus.defaultForRequired(Boolean.TRUE.equals(task.getHumanReviewRequired())).code();
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
