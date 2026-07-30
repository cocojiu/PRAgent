package com.repoguard.agent.review;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ReviewTaskStateMachine {

    public boolean canStartReview(String status) {
        return ReviewTaskStatus.QUEUED == ReviewTaskStatus.from(status);
    }

    public String statusWhenReviewing() {
        return ReviewTaskStatus.REVIEWING.code();
    }

    public String statusWhenQueued() {
        return ReviewTaskStatus.QUEUED.code();
    }

    public String statusWhenPublishFailed() {
        return ReviewTaskStatus.PUBLISH_FAILED.code();
    }

    public String statusWhenExecutionTimeout() {
        return ReviewTaskStatus.EXECUTION_TIMEOUT.code();
    }

    public String statusWhenRequeuePending() {
        return ReviewTaskStatus.REQUEUE_PENDING.code();
    }

    public String statusWhenFailed() {
        return ReviewTaskStatus.FAILED.code();
    }

    public String statusWhenSuperseded() {
        return ReviewTaskStatus.SUPERSEDED.code();
    }

    public String statusAfterReviewCompleted(boolean humanReviewRequired) {
        return humanReviewRequired
            ? ReviewTaskStatus.PENDING_HUMAN_REVIEW.code()
            : ReviewTaskStatus.COMPLETED.code();
    }

    public void ensureRetryAllowed(String status) {
        ReviewTaskStatus taskStatus = ReviewTaskStatus.from(status);
        if (taskStatus != ReviewTaskStatus.FAILED && taskStatus != ReviewTaskStatus.SUPERSEDED) {
            throw new BusinessException(
                ErrorCode.BAD_REQUEST,
                "Only failed or superseded review tasks can be retried"
            );
        }
    }

    public boolean isSuperseded(String status) {
        return ReviewTaskStatus.SUPERSEDED == ReviewTaskStatus.from(status);
    }

    public void ensurePublishRequeueAllowed(String status, boolean publishClaimed) {
        if (!isManualPublishRequeueCandidate(status)) {
            throw new BusinessException(
                ErrorCode.BAD_REQUEST,
                "Only publish failed or execution timeout message tasks can be requeued"
            );
        }
        if (publishClaimed) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Claimed message tasks cannot be requeued manually");
        }
    }

    public boolean isPublishFailed(String status) {
        return ReviewTaskStatus.PUBLISH_FAILED == ReviewTaskStatus.from(status);
    }

    public boolean isExecutionTimeout(String status) {
        return ReviewTaskStatus.EXECUTION_TIMEOUT == ReviewTaskStatus.from(status);
    }

    public boolean isRequeuePending(String status) {
        return ReviewTaskStatus.REQUEUE_PENDING == ReviewTaskStatus.from(status);
    }

    public boolean isPublishRecoveryCandidate(String status) {
        return ReviewTaskStatus.PUBLISH_FAILED == ReviewTaskStatus.from(status);
    }

    public boolean isManualPublishRequeueCandidate(String status) {
        ReviewTaskStatus taskStatus = ReviewTaskStatus.from(status);
        return taskStatus == ReviewTaskStatus.PUBLISH_FAILED
            || taskStatus == ReviewTaskStatus.EXECUTION_TIMEOUT;
    }

    public List<String> publishRecoveryCandidateStatuses() {
        return List.of(ReviewTaskStatus.PUBLISH_FAILED.code());
    }

    public List<String> publishQueueCandidateStatuses() {
        return List.of(
            ReviewTaskStatus.PUBLISH_FAILED.code(),
            ReviewTaskStatus.QUEUED.code()
        );
    }

    public List<String> dataRetentionCandidateStatuses() {
        return List.of(
            ReviewTaskStatus.COMPLETED.code(),
            ReviewTaskStatus.FAILED.code(),
            ReviewTaskStatus.SUPERSEDED.code(),
            ReviewTaskStatus.APPROVED.code(),
            ReviewTaskStatus.CHANGES_REQUESTED.code(),
            ReviewTaskStatus.REJECTED.code()
        );
    }

    public void ensureHumanReviewAllowed(boolean humanReviewRequired, String humanReviewStatus) {
        if (!humanReviewRequired) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Human review is not required for this task");
        }
        if (HumanReviewStatus.PENDING != HumanReviewStatus.from(humanReviewStatus)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Human review has already been decided");
        }
    }

    public boolean canPublishGithubComments(boolean humanReviewRequired, String humanReviewStatus) {
        if (!humanReviewRequired) {
            return true;
        }
        return HumanReviewStatus.from(humanReviewStatus).allowsGithubCommentPublish();
    }

    public String statusAfterHumanReview(String humanReviewStatus) {
        return switch (HumanReviewStatus.from(humanReviewStatus)) {
            case APPROVED -> ReviewTaskStatus.APPROVED.code();
            case CHANGES_REQUESTED -> ReviewTaskStatus.CHANGES_REQUESTED.code();
            case REJECTED -> ReviewTaskStatus.REJECTED.code();
            default -> ReviewTaskStatus.PENDING_HUMAN_REVIEW.code();
        };
    }
}
