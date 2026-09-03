package com.repoguard.agent.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.github.GithubIntegrationProvider;
import com.repoguard.agent.github.GithubIntegrationSettings;
import com.repoguard.agent.github.GithubPullRequestClient;
import com.repoguard.agent.github.GithubPullRequestSummary;
import com.repoguard.agent.github.GithubRepositoryRef;
import com.repoguard.agent.github.GithubReviewCommentResult;
import com.repoguard.agent.github.checks.GithubCheckRunGateway;
import com.repoguard.agent.review.PullRequestDiff;
import java.util.List;
import org.junit.jupiter.api.Test;

class GithubScmProviderAdapterTest {

    private final GithubPullRequestClient client = mock(GithubPullRequestClient.class);
    private final GithubIntegrationProvider integrationProvider = mock(GithubIntegrationProvider.class);
    private final GithubCheckRunGateway checkRunGateway = mock(GithubCheckRunGateway.class);
    private final GithubScmProviderAdapter adapter = new GithubScmProviderAdapter(
        client, integrationProvider, checkRunGateway
    );

    @Test
    void bridgesExistingGithubSettingsRepositoryQueriesAndDiffOperations() {
        GithubIntegrationSettings settings = settings();
        when(integrationProvider.getSettings()).thenReturn(settings);
        when(client.getConfiguredRepository()).thenReturn(new GithubRepositoryRef("octo", "widgets"));
        when(client.listOpenPullRequests()).thenReturn(List.of(new GithubPullRequestSummary(
            "octo", "widgets", 7, "Improve validation", "feature/validation", "sha-7", "octocat",
            "https://github.com/octo/widgets/pull/7", "2026-09-01T10:00:00Z"
        )));
        ReviewTask task = task();
        PullRequestDiff diff = new PullRequestDiff("octo", "widgets", 7, "sha-7", List.of());
        when(client.fetchPullRequestDiff(task)).thenReturn(diff);
        when(client.fetchPullRequestHeadSha(task)).thenReturn("sha-7");

        assertThat(adapter.providerKey()).isEqualTo("GITHUB");
        assertThat(adapter.settings()).isEqualTo(new ScmIntegrationSettings(
            "GITHUB", "CONFIGURED", "https://api.github.com", "token", null, "octo", "widgets", 4L
        ));
        assertThat(adapter.configuredRepository()).isEqualTo(new ScmRepositoryRef("octo", "widgets"));
        assertThat(adapter.listOpenChangeRequests()).singleElement().satisfies(item -> {
            assertThat(item.provider()).isEqualTo("GITHUB");
            assertThat(item.number()).isEqualTo(7);
            assertThat(item.commit()).isEqualTo("sha-7");
        });
        assertThat(adapter.fetchPullRequestDiff(task)).isEqualTo(diff);
        assertThat(adapter.fetchPullRequestHeadSha(task)).isEqualTo("sha-7");
    }

    @Test
    void rejectsIncompleteRepositoryAndMapsCommentResults() {
        when(client.getConfiguredRepository()).thenReturn(new GithubRepositoryRef(" ", "widgets"));
        assertThat(adapter.configuredRepository()).isNull();
        when(client.getConfiguredRepository()).thenReturn(null);
        assertThat(adapter.configuredRepository()).isNull();

        ReviewTask task = task();
        ScmCommentDraft lineDraft = new ScmCommentDraft(19L, "src/App.java", 4, "Please validate input");
        when(client.publishPullRequestComments(eq(task), any())).thenReturn(List.of(
            new GithubReviewCommentResult(19L, "src/App.java", 4, "line", true, "PUBLISHED", "ok",
                "https://github.com/comment/19", 19L)
        ));
        assertThat(adapter.publishComment(task, lineDraft))
            .extracting(ScmCommentResult::success, ScmCommentResult::remoteId)
            .containsExactly(true, 19L);

        when(client.publishPullRequestComments(eq(task), any())).thenReturn(List.of());
        assertThat(adapter.publishComment(task, new ScmCommentDraft(20L, null, null, "summary")))
            .extracting(ScmCommentResult::success, ScmCommentResult::status)
            .containsExactly(false, "FAILED");
        assertThatThrownBy(() -> adapter.publishComment(task, new ScmCommentDraft(1L, null, null, " ")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mapsStatusLifecycleToGithubChecksAndValidatesInputs() {
        ReviewTask task = task();
        when(integrationProvider.getSettingsForRepository("octo", "widgets")).thenReturn(settings());
        when(checkRunGateway.create(any(), eq("https://api.github.com"), eq("octo"), eq("widgets"), any()))
            .thenReturn(new GithubCheckRunGateway.RemoteCheckRun(55L, "external", "queued", null));

        assertThat(adapter.publishStatus(task, new ScmStatusRequest("RepoGuard", "pending", null, null)).state())
            .isEqualTo("pending");
        assertThat(adapter.publishStatus(task, new ScmStatusRequest("RepoGuard", "success", "passed", null)).state())
            .isEqualTo("success");
        assertThat(adapter.publishStatus(task, new ScmStatusRequest("RepoGuard", "action_required", null, null)).state())
            .isEqualTo("action_required");
        assertThat(adapter.publishStatus(task, new ScmStatusRequest("RepoGuard", "canceled", null, null)).state())
            .isEqualTo("canceled");
        assertThat(adapter.publishStatus(task, new ScmStatusRequest("RepoGuard", "queued", null, null)).state())
            .isEqualTo("queued");
        verify(checkRunGateway, times(3)).update(
            any(), eq("https://api.github.com"), eq("octo"), eq("widgets"), eq(55L), any()
        );

        when(checkRunGateway.create(any(), any(), any(), any(), any())).thenReturn(null);
        adapter.publishStatus(task, new ScmStatusRequest("RepoGuard", "failure", null, null));

        assertThatThrownBy(() -> adapter.publishStatus(null, new ScmStatusRequest("name", "success", null, null)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.publishStatus(task, new ScmStatusRequest("name", " ", null, null)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void preservesEmptyGithubStatusSettingsFallbackAndNullCommentResult() {
        when(integrationProvider.getSettings()).thenReturn(GithubIntegrationSettings.empty());
        assertThat(adapter.settings()).isEqualTo(new ScmIntegrationSettings(
            "GITHUB", null, null, null, null, null, null, null
        ));
        ReviewTask task = task();
        when(client.publishPullRequestComments(eq(task), any())).thenReturn(java.util.Collections.singletonList(null));
        assertThat(adapter.publishComment(task, new ScmCommentDraft(1L, null, null, "note")).message())
            .contains("no comment result");
        verifyNoInteractions(checkRunGateway);
    }

    private GithubIntegrationSettings settings() {
        return new GithubIntegrationSettings(
            "GITHUB", "CONFIGURED", "https://api.github.com", "token", null, "octo", "widgets", 4L
        );
    }

    private ReviewTask task() {
        ReviewTask task = new ReviewTask();
        task.setId(42L);
        task.setOrganization("octo");
        task.setRepository("widgets");
        task.setPrNumber(7);
        task.setCommitSha("sha-7");
        return task;
    }
}
