package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.github.GithubPullRequestClient;
import com.repoguard.agent.github.GithubPullRequestSummary;
import com.repoguard.agent.github.GithubRepositoryRef;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class GithubPullRequestOptionServiceImplTest {

    @Test
    void listConfiguredGithubPullRequestsFiltersPullRequestsWithExistingReviewTask() {
        GithubPullRequestClient githubPullRequestClient = mock(GithubPullRequestClient.class);
        ReviewTaskMapper reviewTaskMapper = mock(ReviewTaskMapper.class);
        when(githubPullRequestClient.getConfiguredRepository())
            .thenReturn(new GithubRepositoryRef("cocojiu", "PRAgent"));
        when(githubPullRequestClient.listOpenPullRequests()).thenReturn(List.of(
            pullRequest(42, "queued review", "sha-auto-reviewed"),
            pullRequest(43, "new review", "sha-new")
        ));
        when(reviewTaskMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
            reviewTask(42, "sha-auto-reviewed")
        ));

        GithubPullRequestOptionServiceImpl service = new GithubPullRequestOptionServiceImpl(
            githubPullRequestClient,
            reviewTaskMapper
        );

        var response = service.listConfiguredGithubPullRequests();

        assertThat(response.organization()).isEqualTo("cocojiu");
        assertThat(response.repository()).isEqualTo("PRAgent");
        assertThat(response.items()).extracting("number").containsExactly(43);
        assertThat(response.items().getFirst().headSha()).isEqualTo("sha-new");
    }

    private GithubPullRequestSummary pullRequest(Integer number, String title, String commit) {
        return new GithubPullRequestSummary(
            "cocojiu",
            "PRAgent",
            number,
            title,
            "PRAgent-test",
            commit,
            "octocat",
            "https://github.com/cocojiu/PRAgent/pull/" + number,
            "2026-06-23T08:00:00Z"
        );
    }

    private ReviewTask reviewTask(Integer prNumber, String commitSha) {
        ReviewTask task = new ReviewTask();
        task.setPrNumber(prNumber);
        task.setCommitSha(commitSha);
        return task;
    }
}
