package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.github.GithubChangedFile;
import java.util.List;
import org.junit.jupiter.api.Test;

class DiffHunkLineAllocatorTest {

    private final DiffHunkLineAllocator allocator = new DiffHunkLineAllocator(new DiffHunkSplitter());

    @Test
    void allocatesSourceLinesProportionallyAndAssignsRemainderToLastHunk() {
        GithubChangedFile file = new GithubChangedFile("src/main/java/A.java", "modified", 10, 2, "");
        List<String> hunks = List.of(
            """
                @@ -1,3 +1,5 @@ first
                -old
                +new
                +newer
                """,
            """
                @@ -20,3 +22,6 @@ second
                -legacy
                +added
                """
        );

        List<DiffHunkLineAllocation> allocations = allocator.allocate(file, hunks);

        assertThat(allocations).hasSize(2);
        assertThat(allocations).extracting(DiffHunkLineAllocation::additions)
            .containsExactly(7, 3);
        assertThat(allocations).extracting(DiffHunkLineAllocation::deletions)
            .containsExactly(1, 1);
    }

    @Test
    void fallsBackToVisiblePatchLinesWhenSourceCountsAreMissing() {
        GithubChangedFile file = new GithubChangedFile("src/main/java/A.java", "modified", null, null, "");
        List<String> hunks = List.of(
            """
                @@ -1,3 +1,5 @@ first
                -old
                +new
                +newer
                """
        );

        List<DiffHunkLineAllocation> allocations = allocator.allocate(file, hunks);

        assertThat(allocations).singleElement()
            .satisfies(allocation -> {
                assertThat(allocation.additions()).isEqualTo(2);
                assertThat(allocation.deletions()).isEqualTo(1);
            });
    }
}
