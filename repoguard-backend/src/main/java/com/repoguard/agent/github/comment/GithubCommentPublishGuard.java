package com.repoguard.agent.github.comment;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.review.HumanReviewStatus;
import com.repoguard.agent.review.ReviewTaskStateMachine;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Keeps GitHub comment publish eligibility rules independent from the publish execution flow.
 */
@Component
public class GithubCommentPublishGuard {

    private final ReviewTaskStateMachine reviewTaskStateMachine;

    public GithubCommentPublishGuard(ReviewTaskStateMachine reviewTaskStateMachine) {
        this.reviewTaskStateMachine = Objects.requireNonNull(reviewTaskStateMachine, "reviewTaskStateMachine");
    }

    public void ensurePublishAllowed(ReviewTask task) {
        if (reviewTaskStateMachine.isSuperseded(task.getStatus())) {
            throw new BusinessException(
                ErrorCode.BAD_REQUEST,
                "Superseded review tasks cannot publish GitHub comments"
            );
        }
        String humanReviewStatus = resolveHumanReviewStatus(task);
        if (reviewTaskStateMachine.canPublishGithubComments(
            Boolean.TRUE.equals(task.getHumanReviewRequired()),
            humanReviewStatus
        )) {
            return;
        }
        throw new BusinessException(
            ErrorCode.BAD_REQUEST,
            "Human review approval or changes request is required before publishing GitHub comments"
        );
    }

    String resolveHumanReviewStatus(ReviewTask task) {
        if (StringUtils.hasText(task.getHumanReviewStatus())) {
            return HumanReviewStatus.from(task.getHumanReviewStatus()).code();
        }
        return HumanReviewStatus.defaultForRequired(Boolean.TRUE.equals(task.getHumanReviewRequired())).code();
    }
}
