package com.repoguard.agent.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.github.GithubChangedFile;
import com.repoguard.agent.github.GithubPullRequestDiff;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReviewExecutionDiffStatsTest {

    private final ReviewExecutionDiffStats stats = new ReviewExecutionDiffStats();

    @Test
    void countsFilesAndTreatsNullLineCountsAsZero() {
        GithubPullRequestDiff diff = new GithubPullRequestDiff(
            "repo-guard-demo",
            "spring-boot-demo",
            512,
            List.of(
                new GithubChangedFile("src/App.java", "MODIFY", 10, null, null),
                new GithubChangedFile("README.md", "MODIFY", null, 3, null)
            )
        );

        assertThat(stats.fileCount(diff)).isEqualTo(2);
        assertThat(stats.totalAdditions(diff)).isEqualTo(10);
        assertThat(stats.totalDeletions(diff)).isEqualTo(3);
    }

    @Test
    void treatsMissingFileListAsEmptyDiff() {
        GithubPullRequestDiff diff = new GithubPullRequestDiff("owner", "repo", 1, null);

        assertThat(stats.fileCount(diff)).isZero();
        assertThat(stats.totalAdditions(diff)).isZero();
        assertThat(stats.totalDeletions(diff)).isZero();
    }
}
