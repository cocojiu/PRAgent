package com.repoguard.agent.github;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.config.GithubDiffBudgetProperties;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class GithubChangedFileBudgetAccumulatorTest {

    @Test
    void truncatesOnePatchOnUtf8BoundaryAndReportsReason() {
        GithubDiffBudgetProperties properties = properties();
        properties.setMaxPatchBytes(5);
        GithubChangedFileBudgetAccumulator accumulator = new GithubChangedFileBudgetAccumulator(properties);

        boolean wantsMore = accumulator.acceptPage(
            List.of(new GithubChangedFile("a.java", "modified", 1, 0, "ab中cd")),
            false
        );
        GithubChangedFileFetch result = accumulator.finish(new GithubPaginator.PageTraversal(1, false, false));

        assertThat(wantsMore).isTrue();
        assertThat(result.files().getFirst().patch()).isEqualTo("ab中");
        assertThat(result.files().getFirst().patch().getBytes(StandardCharsets.UTF_8)).hasSize(5);
        assertThat(result.truncation().reasons())
            .containsExactly(GithubDiffTruncation.Reason.MAX_PATCH_BYTES);
    }

    @Test
    void stopsInsideCurrentPageBeforeFileAndByteBudgetsCanGrowUnbounded() {
        GithubDiffBudgetProperties fileProperties = properties();
        fileProperties.setMaxFiles(1);
        GithubChangedFileBudgetAccumulator fileAccumulator =
            new GithubChangedFileBudgetAccumulator(fileProperties);

        assertThat(fileAccumulator.acceptPage(List.of(file("a"), file("b")), true)).isFalse();
        GithubChangedFileFetch fileResult =
            fileAccumulator.finish(new GithubPaginator.PageTraversal(1, false, true));
        assertThat(fileResult.files()).hasSize(1);
        assertThat(fileResult.truncation().reasons())
            .containsExactly(GithubDiffTruncation.Reason.MAX_FILES);

        GithubDiffBudgetProperties byteProperties = properties();
        byteProperties.setMaxTotalBytes(55);
        GithubChangedFileBudgetAccumulator byteAccumulator =
            new GithubChangedFileBudgetAccumulator(byteProperties);
        assertThat(byteAccumulator.acceptPage(List.of(file("a"), file("b")), true)).isFalse();
        GithubChangedFileFetch byteResult =
            byteAccumulator.finish(new GithubPaginator.PageTraversal(1, false, true));
        assertThat(byteResult.files()).hasSizeLessThanOrEqualTo(1);
        assertThat(byteResult.truncation().reasons())
            .containsExactly(GithubDiffTruncation.Reason.MAX_TOTAL_BYTES);
    }

    @Test
    void reportsPageLimitAsExplicitDegradationReason() {
        GithubChangedFileBudgetAccumulator accumulator =
            new GithubChangedFileBudgetAccumulator(properties());
        accumulator.acceptPage(List.of(file("a")), true);

        GithubChangedFileFetch result =
            accumulator.finish(new GithubPaginator.PageTraversal(1, true, false));

        assertThat(result.truncation().truncated()).isTrue();
        assertThat(result.truncation().summary())
            .contains("reasons=max_pages")
            .contains("pages=1")
            .contains("files=1");
    }

    @Test
    void stopsAtElapsedBudgetBeforeRetainingAResponseThatArrivedTooLate() {
        GithubDiffBudgetProperties properties = properties();
        properties.setTotalTimeoutMs(1);
        AtomicLong now = new AtomicLong(10L);
        GithubChangedFileBudgetAccumulator accumulator =
            new GithubChangedFileBudgetAccumulator(properties, now::get);
        now.set(1_000_010L);

        assertThat(accumulator.acceptPage(List.of(file("late")), false)).isFalse();
        GithubChangedFileFetch result =
            accumulator.finish(new GithubPaginator.PageTraversal(1, false, true));

        assertThat(result.files()).isEmpty();
        assertThat(result.truncation().reasons())
            .containsExactly(GithubDiffTruncation.Reason.TOTAL_TIMEOUT);
    }

    private GithubDiffBudgetProperties properties() {
        GithubDiffBudgetProperties properties = new GithubDiffBudgetProperties();
        properties.setMaxPages(10);
        properties.setMaxFiles(100);
        properties.setMaxTotalBytes(1_000_000);
        properties.setMaxPatchBytes(1_000);
        properties.setTotalTimeoutMs(90_000);
        return properties;
    }

    private GithubChangedFile file(String name) {
        return new GithubChangedFile(name, "modified", 1, 0, "p");
    }
}
