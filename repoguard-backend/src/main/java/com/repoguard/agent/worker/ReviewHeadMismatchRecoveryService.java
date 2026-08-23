package com.repoguard.agent.worker;

import com.repoguard.agent.dto.ManualReviewRequest;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.github.GithubPullRequestHeadChangedException;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.review.ReviewTaskSource;
import com.repoguard.agent.review.task.ManualReviewCreationService;
import com.repoguard.agent.review.task.ReviewPullRequestGenerationCoordinator;
import java.time.LocalDateTime;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Repairs the local pull-request head fence from an authoritative GitHub read
 * and guarantees that the current commit has pending work after a stale task is retired.
 */
@Component
class ReviewHeadMismatchRecoveryService {

    private final ReviewPullRequestGenerationCoordinator generationCoordinator;
    private final ReviewTaskMapper taskMapper;
    private final ManualReviewCreationService manualReviewCreationService;

    ReviewHeadMismatchRecoveryService(
        ReviewPullRequestGenerationCoordinator generationCoordinator,
        ReviewTaskMapper taskMapper,
        ManualReviewCreationService manualReviewCreationService
    ) {
        this.generationCoordinator = Objects.requireNonNull(generationCoordinator, "generationCoordinator");
        this.taskMapper = Objects.requireNonNull(taskMapper, "taskMapper");
        this.manualReviewCreationService = Objects.requireNonNull(
            manualReviewCreationService,
            "manualReviewCreationService"
        );
    }

    void recover(ReviewTask staleTask, GithubPullRequestHeadChangedException ex, LocalDateTime recoveredAt) {
        if (!StringUtils.hasText(ex.currentHeadSha()) || ex.currentHeadUpdatedAt() == null) {
            return;
        }
        String currentHeadSha = ex.currentHeadSha().trim().toLowerCase(java.util.Locale.ROOT);
        ReviewPullRequestGenerationCoordinator.GenerationAdvanceResult result = generationCoordinator.advance(
            staleTask.getOrganization(),
            staleTask.getRepository(),
            staleTask.getPrNumber(),
            currentHeadSha,
            recoveredAt,
            ex.currentHeadUpdatedAt()
        );
        if (!result.accepted()) {
            return;
        }

        taskMapper.prepareCurrentHeadTaskForRepublish(
            staleTask.getOrganization(),
            staleTask.getRepository(),
            staleTask.getPrNumber(),
            currentHeadSha,
            result.generation(),
            recoveredAt
        );
        manualReviewCreationService.triggerWebhookReview(
            new ManualReviewRequest(
                staleTask.getOrganization(),
                staleTask.getRepository(),
                staleTask.getPrNumber(),
                staleTask.getTitle(),
                currentHeadSha,
                staleTask.getBranchName(),
                ReviewTaskSource.GITHUB_WEBHOOK.code()
            ),
            ex.currentHeadUpdatedAt()
        );
    }
}
