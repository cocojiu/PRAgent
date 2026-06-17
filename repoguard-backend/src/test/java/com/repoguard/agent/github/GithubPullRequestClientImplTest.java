package com.repoguard.agent.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.repoguard.agent.config.GithubIntegrationProvider;
import com.repoguard.agent.config.GithubIntegrationSettings;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class GithubPullRequestClientImplTest {

    private final GithubIntegrationProvider githubIntegrationProvider = org.mockito.Mockito.mock(GithubIntegrationProvider.class);
    private final GithubPullRequestClientImpl client = new GithubPullRequestClientImpl(
        githubIntegrationProvider,
        RestClient.builder()
    );

    @Test
    void getConfiguredRepositoryReturnsTrimmedProviderRepository() {
        when(githubIntegrationProvider.getSettings()).thenReturn(new GithubIntegrationSettings(
            "GITHUB",
            "CONFIGURED",
            "https://api.github.com",
            "ghp_test",
            null,
            " octocat ",
            " api ",
            7L
        ));

        GithubRepositoryRef repository = client.getConfiguredRepository();

        assertThat(repository.owner()).isEqualTo("octocat");
        assertThat(repository.repository()).isEqualTo("api");
    }
}
