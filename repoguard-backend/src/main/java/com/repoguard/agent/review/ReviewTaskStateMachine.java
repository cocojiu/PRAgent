package com.repoguard.agent.review;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import org.springframework.stereotype.Component;

@Component
public class ReviewTaskStateMachine {

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
