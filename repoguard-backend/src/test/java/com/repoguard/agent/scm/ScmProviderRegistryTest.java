package com.repoguard.agent.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.review.PullRequestDiff;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScmProviderRegistryTest {

    @Test
    void resolvesProvidersCaseInsensitivelyAndReturnsSortedDescriptors() {
        ScmProvider gitlab = provider("gitlab", true, "group/service");
        ScmProvider github = provider("GITHUB", false, null);

        ScmProviderRegistry registry = new ScmProviderRegistry(List.of(gitlab, github));

        assertThat(registry.require(" GitLab ")).isSameAs(gitlab);
        assertThat(registry.descriptors())
            .extracting(ScmProviderRegistry.ScmProviderDescriptor::provider)
            .containsExactly("GITHUB", "gitlab");
        assertThat(registry.descriptors().get(1).repository()).isEqualTo("group/service");
    }

    @Test
    void rejectsDuplicateAndUnsupportedProviders() {
        ScmProvider first = provider("GITHUB", true, null);
        ScmProvider second = provider(" github ", true, null);

        assertThatThrownBy(() -> new ScmProviderRegistry(List.of(first, second)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Duplicate SCM provider");

        ScmProviderRegistry registry = new ScmProviderRegistry(List.of(first));
        assertThatThrownBy(() -> registry.require("gitee"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unsupported SCM provider");
    }

    private ScmProvider provider(String key, boolean configured, String repository) {
        return new ScmProvider() {
            @Override
            public String providerKey() {
                return key;
            }

            @Override
            public ScmIntegrationSettings settings() {
                return new ScmIntegrationSettings(key, configured ? "CONFIGURED" : "NOT_CONFIGURED",
                    "https://scm.example", configured ? "token" : null, null, null, null, 1L);
            }

            @Override
            public ScmRepositoryRef configuredRepository() {
                if (repository == null) {
                    return null;
                }
                int separator = repository.indexOf('/');
                return new ScmRepositoryRef(repository.substring(0, separator), repository.substring(separator + 1));
            }

            @Override
            public List<ScmChangeRequestSummary> listOpenChangeRequests() {
                return List.of();
            }

            @Override
            public PullRequestDiff fetchPullRequestDiff(ReviewTask task) {
                return new PullRequestDiff("group", "repo", 1, List.of());
            }

            @Override
            public String fetchPullRequestHeadSha(ReviewTask task) {
                return "sha";
            }

            @Override
            public ScmCommentResult publishComment(ReviewTask task, ScmCommentDraft draft) {
                return new ScmCommentResult(key, null, true, "PUBLISHED", "ok", null, null);
            }

            @Override
            public ScmStatusResult publishStatus(ReviewTask task, ScmStatusRequest request) {
                return new ScmStatusResult(key, true, "success", "ok", null);
            }
        };
    }
}
