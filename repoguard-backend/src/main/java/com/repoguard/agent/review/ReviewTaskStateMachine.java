package com.repoguard.agent.review;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import org.springframework.stereotype.Component;

@Component
public class ReviewTaskStateMachine {

    public boolean canStartReview(String status) {
        return ReviewTaskStatus.QUEUED == ReviewTaskStatus.from(status);
    }

    public String statusWhenReviewing() {
        return ReviewTaskStatus.REVIEWING.code();
    }

    public String statusAfterReviewCompleted(boolean humanReviewRequired) {
        return humanReviewRequired
            ? ReviewTaskStatus.PENDING_HUMAN_REVIEW.code()
            : ReviewTaskStatus.COMPLETED.code();
    }

    public void ensureRetryAllowed(String status) {
        if (ReviewTaskStatus.FAILED != ReviewTaskStatus.from(status)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Only failed review tasks can be retried");
        }
    }

    public String statusAfterHumanReview(String humanReviewStatus) {
        return switch (humanReviewStatus) {
            case "APPROVED" -> ReviewTaskStatus.APPROVED.code();
            case "CHANGES_REQUESTED" -> ReviewTaskStatus.CHANGES_REQUESTED.code();
            case "REJECTED" -> ReviewTaskStatus.REJECTED.code();
            default -> ReviewTaskStatus.PENDING_HUMAN_REVIEW.code();
        };
    }
}
