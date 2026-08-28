package com.repoguard.agent.review.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.mapper.ReviewPullRequestHeadMapper;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.timeline.ReviewTimelineAppender;
import com.repoguard.agent.timeline.ReviewTimelineStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReviewPullRequestGenerationCoordinatorTest {

    private final ReviewPullRequestHeadMapper headMapper = org.mockito.Mockito.mock(ReviewPullRequestHeadMapper.class);
    private final ReviewTaskMapper taskMapper = org.mockito.Mockito.mock(ReviewTaskMapper.class);
    private final ReviewTimelineAppender timelineAppender = org.mockito.Mockito.mock(ReviewTimelineAppender.class);
    private final ReviewPullRequestGenerationCoordinator coordinator =
        new ReviewPullRequestGenerationCoordinator(headMapper, taskMapper, timelineAppender);

    @Test
    void advancesHeadAndCompletesTimelinesForSupersededPendingTasks() {
        LocalDateTime now = LocalDateTime.parse("2026-08-15T04:00:00");
        when(headMapper.selectGeneration("org", "repo", 7)).thenReturn(3L);
        when(headMapper.selectLatestCommitSha("org", "repo", 7)).thenReturn("new-sha");
        when(taskMapper.supersedeOlderPendingTasks("org", "repo", 7, 3L, now, "Superseded by pull request generation 3 at commit new-sha"))
            .thenReturn(2);
        when(taskMapper.selectTasksSupersededAtGeneration("org", "repo", 7, 3L, now))
            .thenReturn(List.of(10L, 11L));

        ReviewPullRequestGenerationCoordinator.GenerationAdvanceResult result =
            coordinator.advance("org", "repo", 7, "new-sha", now, now.minusMinutes(1));
        int superseded = coordinator.supersedeOlderPending("org", "repo", 7, result.generation(), "new-sha", now);

        assertThat(result.generation()).isEqualTo(3L);
        assertThat(result.accepted()).isTrue();
        assertThat(superseded).isEqualTo(2);
        verify(headMapper).advance("org", "repo", 7, "new-sha", now, now.minusMinutes(1));
        verify(timelineAppender).completeCurrentAndAppend(
            10L,
            "Superseded by pull request generation 3 at commit new-sha",
            now,
            ReviewTimelineStatus.DONE
        );
        verify(timelineAppender).completeCurrentAndAppend(
            11L,
            "Superseded by pull request generation 3 at commit new-sha",
            now,
            ReviewTimelineStatus.DONE
        );
    }

    @Test
    void reportsOlderWebhookAsRejectedWithoutChangingResolvedGeneration() {
        LocalDateTime now = LocalDateTime.parse("2026-08-15T04:00:00");
        when(headMapper.selectGeneration("org", "repo", 7)).thenReturn(4L);
        when(headMapper.selectLatestCommitSha("org", "repo", 7)).thenReturn("newer-sha");

        ReviewPullRequestGenerationCoordinator.GenerationAdvanceResult result =
            coordinator.advance("org", "repo", 7, "stale-sha", now, now.minusHours(1));

        assertThat(result.generation()).isEqualTo(4L);
        assertThat(result.accepted()).isFalse();
        assertThat(result.latestCommitSha()).isEqualTo("newer-sha");
    }
}
