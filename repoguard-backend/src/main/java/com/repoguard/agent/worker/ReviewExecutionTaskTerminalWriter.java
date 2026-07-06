package com.repoguard.agent.worker;

import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.review.ReviewResult;
import java.time.LocalDateTime;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
class ReviewExecutionTaskTerminalWriter {

    private final ReviewTaskMapper reviewTaskMapper;
    private final ReviewTaskClaimService claimService;
    private final ReviewTaskCompletionApplier completionApplier;
    private final ReviewExecutionClock clock;

    ReviewExecutionTaskTerminalWriter(
        ReviewTaskMapper reviewTaskMapper,
        ReviewTaskClaimService claimService,
        ReviewTaskCompletionApplier completionApplier,
        ReviewExecutionClock clock
    ) {
        this.reviewTaskMapper = reviewTaskMapper;
        this.claimService = Objects.requireNonNull(claimService, "claimService");
        this.completionApplier = Objects.requireNonNull(completionApplier, "completionApplier");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    CompletedTaskWrite applyCompleted(
        ReviewTask task,
        ReviewResult reviewResult,
        LocalDateTime startedAt,
        String claimId
    ) {
        LocalDateTime finishedAt = clock.now();
        boolean humanReviewRequired = completionApplier.applyCompleted(task, reviewResult, startedAt, finishedAt);
        claimService.ensureClaimOwnedAndFenceTerminalStatus(task, claimId);
        claimService.releaseReviewClaim(task);
        reviewTaskMapper.updateById(task);
        return new CompletedTaskWrite(finishedAt, humanReviewRequired);
    }

    FailedTaskWrite applyFailed(ReviewTask task, LocalDateTime startedAt, String claimId) {
        LocalDateTime failedAt = clock.now();
        completionApplier.applyFailed(task, startedAt, failedAt);
        if (!claimService.fenceTerminalStatus(task, claimId)) {
            return new FailedTaskWrite(failedAt, false);
        }
        claimService.releaseReviewClaim(task);
        reviewTaskMapper.updateById(task);
        return new FailedTaskWrite(failedAt, true);
    }

    record CompletedTaskWrite(LocalDateTime finishedAt, boolean humanReviewRequired) {
    }

    record FailedTaskWrite(LocalDateTime failedAt, boolean applied) {
    }
}
