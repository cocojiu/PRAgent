package com.repoguard.agent.github.comment;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.review.execution.ReviewFindingComparisonService;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Materializes the current attempt comparison before any GitHub writeback is planned.
 * A missing comparison is fail-closed so an unclassified finding cannot be published.
 */
@Component
public class GithubCommentComparisonGate {

    private final ReviewFindingComparisonService comparisonService;
    private final boolean enabled;

    public GithubCommentComparisonGate(ReviewFindingComparisonService comparisonService) {
        this.comparisonService = Objects.requireNonNull(comparisonService, "comparisonService");
        this.enabled = true;
    }

    private GithubCommentComparisonGate() {
        this.comparisonService = null;
        this.enabled = false;
    }

    static GithubCommentComparisonGate disabled() {
        return new GithubCommentComparisonGate();
    }

    public void ensureCompared(ReviewTask task) {
        if (!enabled) {
            return;
        }
        if (task == null || task.getId() == null) {
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND, "Review task is required before GitHub writeback");
        }
        if (task.getCurrentAttemptId() == null) {
            throw new BusinessException(
                ErrorCode.BAD_REQUEST,
                "GitHub writeback requires a current review execution attempt"
            );
        }
        comparisonService.compare(task.getId(), null, task.getCurrentAttemptId(), 1, 1);
    }
}
