package com.repoguard.agent.worker;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.dto.ManualReviewRequest;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.github.GithubPullRequestHeadChangedException;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.review.task.ManualReviewCreationService;
import com.repoguard.agent.review.task.ReviewPullRequestGenerationCoordinator;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ReviewHeadMismatchRecoveryServiceTest {

    private static final String CURRENT_SHA = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    @Test
    void repairsFenceAndEnsuresCurrentHeadTaskIsPending() {
        ReviewPullRequestGenerationCoordinator coordinator = mock(ReviewPullRequestGenerationCoordinator.class);
        ReviewTaskMapper taskMapper = mock(ReviewTaskMapper.class);
        ManualReviewCreationService creationService = mock(ManualReviewCreationService.class);
        LocalDateTime githubUpdatedAt = LocalDateTime.of(2026, 8, 15, 3, 0);
        LocalDateTime recoveredAt = LocalDateTime.of(2026, 8, 15, 3, 1);
        when(coordinator.advance("acme", "api", 7, CURRENT_SHA, recoveredAt, githubUpdatedAt))
            .thenReturn(new ReviewPullRequestGenerationCoordinator.GenerationAdvanceResult(5L, true, CURRENT_SHA));

        new ReviewHeadMismatchRecoveryService(coordinator, taskMapper, creationService).recover(
            task(),
            new GithubPullRequestHeadChangedException(
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                CURRENT_SHA,
                githubUpdatedAt
            ),
            recoveredAt
        );

        verify(taskMapper).prepareCurrentHeadTaskForRepublish(
            "acme",
            "api",
            7,
            CURRENT_SHA,
            5L,
            recoveredAt
        );
        ArgumentCaptor<ManualReviewRequest> request = ArgumentCaptor.forClass(ManualReviewRequest.class);
        verify(creationService).triggerWebhookReview(request.capture(), org.mockito.ArgumentMatchers.eq(githubUpdatedAt));
        org.assertj.core.api.Assertions.assertThat(request.getValue().commit()).isEqualTo(CURRENT_SHA);
        org.assertj.core.api.Assertions.assertThat(request.getValue().source()).isEqualTo("GITHUB_WEBHOOK");
    }

    @Test
    void doesNotReplaceANewerFenceWhenAuthoritativeReadLostARace() {
        ReviewPullRequestGenerationCoordinator coordinator = mock(ReviewPullRequestGenerationCoordinator.class);
        ReviewTaskMapper taskMapper = mock(ReviewTaskMapper.class);
        ManualReviewCreationService creationService = mock(ManualReviewCreationService.class);
        LocalDateTime githubUpdatedAt = LocalDateTime.of(2026, 8, 15, 3, 0);
        LocalDateTime recoveredAt = LocalDateTime.of(2026, 8, 15, 3, 1);
        when(coordinator.advance("acme", "api", 7, CURRENT_SHA, recoveredAt, githubUpdatedAt))
            .thenReturn(new ReviewPullRequestGenerationCoordinator.GenerationAdvanceResult(
                6L,
                false,
                "cccccccccccccccccccccccccccccccccccccccc"
            ));

        new ReviewHeadMismatchRecoveryService(coordinator, taskMapper, creationService).recover(
            task(),
            new GithubPullRequestHeadChangedException(
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                CURRENT_SHA,
                githubUpdatedAt
            ),
            recoveredAt
        );

        verify(taskMapper, never()).prepareCurrentHeadTaskForRepublish(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any()
        );
        verify(creationService, never()).triggerWebhookReview(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any()
        );
    }

    private ReviewTask task() {
        ReviewTask task = new ReviewTask();
        task.setOrganization("acme");
        task.setRepository("api");
        task.setPrNumber(7);
        task.setTitle("PR title");
        task.setBranchName("feature");
        return task;
    }
}
