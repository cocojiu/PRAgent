package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.repoguard.agent.config.LlmReviewContextProperties;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RepositorySemanticContextProviderTest {

    @Test
    void convertsSnapshotFilesToRelatedSlicesAndPreservesSummary() {
        RepositorySemanticRepository repository = mock(RepositorySemanticRepository.class);
        LlmReviewContextProperties properties = new LlmReviewContextProperties();
        RepositorySemanticContextProvider provider = new RepositorySemanticContextProvider(repository, properties);
        PullRequestChangedFile primary = new PullRequestChangedFile(
            "src/main/java/OrderService.java", "modified", 1, 0,
            "@@ -1,0 +1,1 @@\n+class OrderService {}",
            ChangedFileContext.available("src/main/java/OrderService.java", "head", "class OrderService {}")
        );
        PullRequestDiff diff = new PullRequestDiff("octo", "repo", 1, "head", List.of(primary));
        when(repository.fetch(any(), any())).thenReturn(new RepositorySemanticSnapshot(
            "main",
            List.of(
                new RepositorySemanticFile("src/main/java/OrderFacade.java", "class OrderFacade { OrderService service; }"),
                new RepositorySemanticFile("src/main/resources/application.yml", "security:\n  role: ADMIN"),
                new RepositorySemanticFile("docs/README.md", "OrderService")
            ),
            List.of(new RepositorySemanticLimitation("[repository]", "DEGRADED", "tree_truncated")),
            true,
            "branch=main; deterministic=true"
        ));

        RepositorySemanticContext context = provider.load(diff);

        assertThat(context.slices()).extracting(LlmContextSlice::filePath)
            .containsExactlyInAnyOrder("src/main/java/OrderFacade.java", "src/main/resources/application.yml");
        assertThat(context.summary()).contains("branch=main", "indexedFiles=2");
        assertThat(context.truncated()).isTrue();
        assertThat(context.limitations()).singleElement()
            .extracting(LlmReviewContext.ContextLimitation::reason).isEqualTo("tree_truncated");
    }

    @Test
    void degradesWhenRepositoryPortFailsAndSkipsDisabledIndex() {
        RepositorySemanticRepository repository = mock(RepositorySemanticRepository.class);
        LlmReviewContextProperties properties = new LlmReviewContextProperties();
        RepositorySemanticContextProvider provider = new RepositorySemanticContextProvider(repository, properties);
        PullRequestDiff diff = new PullRequestDiff("octo", "repo", 1, "head", List.of());
        when(repository.fetch(any(), any())).thenThrow(new IllegalStateException("unavailable"));

        RepositorySemanticContext degraded = provider.load(diff);
        assertThat(degraded.summary()).contains("semantic_context_provider_failed");
        assertThat(degraded.limitations()).singleElement()
            .extracting(LlmReviewContext.ContextLimitation::status).isEqualTo("UNAVAILABLE");

        clearInvocations(repository);
        properties.setSemanticIndexEnabled(false);
        RepositorySemanticContextProvider disabled = new RepositorySemanticContextProvider(repository, properties);
        assertThat(disabled.load(diff).summary()).isEqualTo("disabled_or_missing_repository");
        verifyNoInteractions(repository);
    }

    @Test
    void snapshotAndFileDefaultsRemainSafe() {
        assertThat(RepositorySemanticSnapshot.empty("empty").files()).isEmpty();
        assertThat(new RepositorySemanticFile(null, null).content()).isEmpty();
        assertThat(new RepositorySemanticLimitation(null, null, null).filePath()).isEqualTo("[repository]");
    }
}
