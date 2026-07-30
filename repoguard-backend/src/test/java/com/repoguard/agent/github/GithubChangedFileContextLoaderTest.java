package com.repoguard.agent.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.config.ReviewContextProperties;
import com.repoguard.agent.external.ExternalCallResilience;
import com.repoguard.agent.review.ChangedFileContext;
import com.repoguard.agent.review.PullRequestChangedFile;
import com.repoguard.agent.review.ReviewFilePolicy;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class GithubChangedFileContextLoaderTest {

    private final GithubChangedFileContentReader contentReader = mock(GithubChangedFileContentReader.class);
    private final ExternalCallResilience resilience = mock(ExternalCallResilience.class);
    private final GithubIntegrationSettings settings = GithubIntegrationSettings.empty();

    @Test
    void cachesFullFileContentByExactHeadShaAndPath() {
        ReviewContextProperties properties = new ReviewContextProperties();
        GithubChangedFileContextLoader loader = loader(properties, new AtomicLong()::get);
        PullRequestChangedFile file = controllerFile("src/main/java/com/example/AdminController.java");
        when(contentReader.fetch(
            eq(settings),
            eq("https://api.github.test"),
            eq("owner"),
            eq("repo"),
            eq("head-a"),
            eq(file.filename()),
            eq(resilience)
        )).thenReturn("@RequireRole(\"ADMIN\")\nclass AdminController {}");

        List<PullRequestChangedFile> first = load(loader, "head-a", List.of(file));
        List<PullRequestChangedFile> second = load(loader, "head-a", List.of(file));

        assertThat(first.getFirst().context().status()).isEqualTo(ChangedFileContext.Status.AVAILABLE);
        assertThat(first.getFirst().context().content()).contains("@RequireRole");
        assertThat(second.getFirst().context().content()).isEqualTo(first.getFirst().context().content());
        verify(contentReader).fetch(
            settings,
            "https://api.github.test",
            "owner",
            "repo",
            "head-a",
            file.filename(),
            resilience
        );
    }

    @Test
    void doesNotReuseContextAcrossDifferentHeadShas() {
        ReviewContextProperties properties = new ReviewContextProperties();
        GithubChangedFileContextLoader loader = loader(properties, new AtomicLong()::get);
        PullRequestChangedFile file = controllerFile("src/main/java/com/example/AdminController.java");
        when(contentReader.fetch(any(), any(), any(), any(), eq("head-a"), any(), any()))
            .thenReturn("class AdminController {}");
        when(contentReader.fetch(any(), any(), any(), any(), eq("head-b"), any(), any()))
            .thenReturn("@RequireRole(\"ADMIN\")\nclass AdminController {}");

        List<PullRequestChangedFile> first = load(loader, "head-a", List.of(file));
        List<PullRequestChangedFile> second = load(loader, "head-b", List.of(file));

        assertThat(first.getFirst().context().content()).doesNotContain("@RequireRole");
        assertThat(second.getFirst().context().content()).contains("@RequireRole");
    }

    @Test
    void appliesFileCountAndTotalByteBudgetsWithoutFailingTheDiff() {
        ReviewContextProperties properties = new ReviewContextProperties();
        properties.setMaxFiles(1);
        properties.setMaxTotalBytes(65_536);
        GithubChangedFileContextLoader loader = loader(properties, new AtomicLong()::get);
        PullRequestChangedFile first = controllerFile("src/main/java/com/example/FirstController.java");
        PullRequestChangedFile second = controllerFile("src/main/java/com/example/SecondController.java");
        when(contentReader.fetch(any(), any(), any(), any(), any(), eq(first.filename()), any()))
            .thenReturn("class FirstController {}");

        List<PullRequestChangedFile> loaded = load(loader, "head-a", List.of(first, second));

        assertThat(loaded.get(0).context().status()).isEqualTo(ChangedFileContext.Status.AVAILABLE);
        assertThat(loaded.get(1).context().status()).isEqualTo(ChangedFileContext.Status.BUDGET_EXCEEDED);
        assertThat(loaded.get(1).context().reason()).isEqualTo("max_files");
    }

    @Test
    void recordsFetchFailureAsUnavailableCandidateContext() {
        ReviewContextProperties properties = new ReviewContextProperties();
        GithubChangedFileContextLoader loader = loader(properties, new AtomicLong()::get);
        PullRequestChangedFile file = controllerFile("src/main/java/com/example/AdminController.java");
        when(contentReader.fetch(any(), any(), any(), any(), any(), any(), any()))
            .thenThrow(new IllegalStateException("offline"));

        PullRequestChangedFile loaded = load(loader, "head-a", List.of(file)).getFirst();

        assertThat(loaded.context().status()).isEqualTo(ChangedFileContext.Status.UNAVAILABLE);
        assertThat(loaded.context().reason()).isEqualTo("fetch_failed:IllegalStateException");
    }

    @Test
    void appliesPerFileAndRetainedByteBudgets() {
        ReviewContextProperties perFileProperties = new ReviewContextProperties();
        perFileProperties.setMaxFileBytes(8);
        GithubChangedFileContextLoader perFileLoader = loader(
            perFileProperties,
            new AtomicLong()::get
        );
        PullRequestChangedFile first = controllerFile("src/main/java/com/example/FirstController.java");
        when(contentReader.fetch(any(), any(), any(), any(), any(), eq(first.filename()), any()))
            .thenReturn("class FirstController {}");

        PullRequestChangedFile tooLarge = load(perFileLoader, "head-a", List.of(first)).getFirst();

        assertThat(tooLarge.context().status()).isEqualTo(ChangedFileContext.Status.TOO_LARGE);
        assertThat(tooLarge.context().content()).isEmpty();

        org.mockito.Mockito.reset(contentReader);
        ReviewContextProperties totalProperties = new ReviewContextProperties();
        totalProperties.setMaxTotalBytes(8);
        GithubChangedFileContextLoader totalLoader = loader(totalProperties, new AtomicLong()::get);
        when(contentReader.fetch(any(), any(), any(), any(), any(), eq(first.filename()), any()))
            .thenReturn("class FirstController {}");

        PullRequestChangedFile overTotal = load(totalLoader, "head-a", List.of(first)).getFirst();

        assertThat(overTotal.context().status()).isEqualTo(ChangedFileContext.Status.BUDGET_EXCEEDED);
        assertThat(overTotal.context().reason()).isEqualTo("max_total_bytes");
    }

    @Test
    void stopsBeforeRemoteReadWhenTotalTimeBudgetIsExhausted() {
        ReviewContextProperties properties = new ReviewContextProperties();
        properties.setTotalTimeoutMs(1);
        AtomicLong time = new AtomicLong();
        GithubChangedFileContextLoader loader = loader(
            properties,
            () -> time.getAndAdd(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(2))
        );

        PullRequestChangedFile loaded = load(
            loader,
            "head-a",
            List.of(controllerFile("src/main/java/com/example/AdminController.java"))
        ).getFirst();

        assertThat(loaded.context().status()).isEqualTo(ChangedFileContext.Status.BUDGET_EXCEEDED);
        assertThat(loaded.context().reason()).isEqualTo("total_timeout");
        org.mockito.Mockito.verifyNoInteractions(contentReader);
    }

    @Test
    void excludesTestAndGeneratedPathsBeforeAnyRemoteRead() {
        ReviewContextProperties properties = new ReviewContextProperties();
        GithubChangedFileContextLoader loader = loader(properties, new AtomicLong()::get);
        PullRequestChangedFile testFile = controllerFile("src/test/java/com/example/AdminController.java");
        PullRequestChangedFile generated = controllerFile("target/generated/AdminController.java");

        List<PullRequestChangedFile> loaded = load(loader, "head-a", List.of(testFile, generated));

        assertThat(loaded).extracting(file -> file.context().status())
            .containsOnly(ChangedFileContext.Status.EXCLUDED);
        org.mockito.Mockito.verifyNoInteractions(contentReader);
    }

    private GithubChangedFileContextLoader loader(
        ReviewContextProperties properties,
        java.util.function.LongSupplier nanoTime
    ) {
        return new GithubChangedFileContextLoader(
            contentReader,
            properties,
            new ReviewFilePolicy(properties),
            nanoTime
        );
    }

    private List<PullRequestChangedFile> load(
        GithubChangedFileContextLoader loader,
        String headSha,
        List<PullRequestChangedFile> files
    ) {
        return loader.load(
            settings,
            "https://api.github.test",
            "owner",
            "repo",
            headSha,
            files,
            resilience
        );
    }

    private PullRequestChangedFile controllerFile(String path) {
        return new PullRequestChangedFile(
            path,
            "modified",
            1,
            0,
            "@@ -1,0 +1,1 @@\n+@PostMapping(\"/users\")"
        );
    }
}
