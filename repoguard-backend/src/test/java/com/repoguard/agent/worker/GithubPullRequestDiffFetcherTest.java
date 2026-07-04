package com.repoguard.agent.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.github.GithubChangedFile;
import com.repoguard.agent.github.GithubPullRequestClient;
import com.repoguard.agent.github.GithubPullRequestDiff;
import com.repoguard.agent.observability.RepoGuardMetrics;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class GithubPullRequestDiffFetcherTest {

    private final GithubPullRequestClient githubPullRequestClient = org.mockito.Mockito.mock(GithubPullRequestClient.class);
    private final RepoGuardMetrics metrics = org.mockito.Mockito.mock(RepoGuardMetrics.class);
    private final TestReviewExecutionClock clock = new TestReviewExecutionClock();
    private final GithubPullRequestDiffFetcher fetcher = new GithubPullRequestDiffFetcher(
        githubPullRequestClient,
        metrics,
        clock
    );

    @Test
    void fetchReturnsDiffAndRecordsSuccessMetric() {
        ReviewTask task = task();
        GithubPullRequestDiff diff = new GithubPullRequestDiff(
            "repo-guard-demo",
            "spring-boot-demo",
            512,
            List.of(new GithubChangedFile("src/App.java", "modified", 3, 1, "patch"))
        );
        when(githubPullRequestClient.fetchPullRequestDiff(task)).thenReturn(diff);
        clock.setTimes("2026-07-04T22:40:00", "2026-07-04T22:40:05");

        GithubPullRequestDiff fetched = fetcher.fetch(task);

        assertThat(fetched).isSameAs(diff);
        verify(githubPullRequestClient).fetchPullRequestDiff(task);
        verify(metrics).githubDiffDuration(Duration.ofSeconds(5), "success");
    }

    @Test
    void fetchRecordsFailureMetricAndPropagatesException() {
        ReviewTask task = task();
        IllegalStateException failure = new IllegalStateException("github unavailable");
        when(githubPullRequestClient.fetchPullRequestDiff(task)).thenThrow(failure);
        clock.setTimes("2026-07-04T22:41:00", "2026-07-04T22:41:03");

        assertThatThrownBy(() -> fetcher.fetch(task)).isSameAs(failure);

        verify(metrics).githubDiffDuration(Duration.ofSeconds(3), "failed");
    }

    private ReviewTask task() {
        ReviewTask task = new ReviewTask();
        task.setId(42L);
        task.setOrganization("repo-guard-demo");
        task.setRepository("spring-boot-demo");
        task.setPrNumber(512);
        return task;
    }

    private static class TestReviewExecutionClock extends ReviewExecutionClock {

        private LocalDateTime[] times = new LocalDateTime[0];
        private int index;

        void setTimes(String... isoDateTimes) {
            times = java.util.Arrays.stream(isoDateTimes)
                .map(LocalDateTime::parse)
                .toArray(LocalDateTime[]::new);
            index = 0;
        }

        @Override
        LocalDateTime now() {
            return times[index++];
        }
    }
}
