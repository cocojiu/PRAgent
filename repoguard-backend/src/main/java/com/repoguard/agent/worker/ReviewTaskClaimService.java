package com.repoguard.agent.worker;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.review.ReviewTaskStateMachine;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class ReviewTaskClaimService {

    private final ReviewTaskMapper reviewTaskMapper;
    private final ReviewTaskStateMachine reviewTaskStateMachine;

    public ReviewTaskClaimService(
        ReviewTaskMapper reviewTaskMapper,
        ReviewTaskStateMachine reviewTaskStateMachine
    ) {
        this.reviewTaskMapper = reviewTaskMapper;
        this.reviewTaskStateMachine = reviewTaskStateMachine == null
            ? new ReviewTaskStateMachine()
            : reviewTaskStateMachine;
    }

    public String newClaimId() {
        return UUID.randomUUID().toString();
    }

    public boolean claimReviewing(ReviewTask task, LocalDateTime startedAt, String claimId) {
        task.setStatus(reviewTaskStateMachine.statusWhenReviewing());
        task.setStartedAt(startedAt);
        task.setReviewClaimedAt(startedAt);
        task.setReviewClaimedBy(claimId);
        int updated = reviewTaskMapper.update(
            new UpdateWrapper<ReviewTask>()
                .eq("id", task.getId())
                .eq("status", reviewTaskStateMachine.statusWhenQueued())
                .set("status", reviewTaskStateMachine.statusWhenReviewing())
                .set("started_at", startedAt)
                .set("review_claimed_at", startedAt)
                .set("review_claimed_by", claimId)
                .set("publish_claimed_at", null)
                .set("publish_claimed_by", null)
        );
        if (updated <= 0) {
            return false;
        }
        task.setPublishClaimedAt(null);
        task.setPublishClaimedBy(null);
        return true;
    }

    public void ensureClaimOwnedAndFenceTerminalStatus(ReviewTask task, String claimId) {
        if (!fenceTerminalStatus(task, claimId)) {
            throw new ReviewTaskClaimLostException();
        }
    }

    public boolean fenceTerminalStatus(ReviewTask task, String claimId) {
        int updated = reviewTaskMapper.update(
            new UpdateWrapper<ReviewTask>()
                .eq("id", task.getId())
                .eq("status", reviewTaskStateMachine.statusWhenReviewing())
                .eq("review_claimed_by", claimId)
                .set("status", task.getStatus())
                .set("review_claimed_at", null)
                .set("review_claimed_by", null)
        );
        return updated > 0;
    }
}
