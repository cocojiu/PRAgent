package com.repoguard.agent.review;

import com.repoguard.agent.github.GithubChangedFile;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
class DiffHunkLineAllocator {

    private final DiffHunkSplitter hunkSplitter;

    DiffHunkLineAllocator(DiffHunkSplitter hunkSplitter) {
        this.hunkSplitter = Objects.requireNonNull(hunkSplitter, "hunkSplitter");
    }

    List<DiffHunkLineAllocation> allocate(GithubChangedFile file, List<String> hunks) {
        List<String> safeHunks = hunks == null ? List.of() : hunks;
        if (safeHunks.isEmpty()) {
            return List.of();
        }

        List<Integer> visibleAdditions = safeHunks.stream()
            .map(hunk -> hunkSplitter.countPatchLines(hunk, '+'))
            .toList();
        List<Integer> visibleDeletions = safeHunks.stream()
            .map(hunk -> hunkSplitter.countPatchLines(hunk, '-'))
            .toList();
        int totalVisibleAdditions = visibleAdditions.stream().mapToInt(Integer::intValue).sum();
        int totalVisibleDeletions = visibleDeletions.stream().mapToInt(Integer::intValue).sum();
        int sourceAdditions = safeInt(file == null ? null : file.additions());
        int sourceDeletions = safeInt(file == null ? null : file.deletions());

        List<DiffHunkLineAllocation> allocations = new ArrayList<>();
        int allocatedAdditions = 0;
        int allocatedDeletions = 0;
        for (int i = 0; i < safeHunks.size(); i++) {
            boolean last = i == safeHunks.size() - 1;
            int additions = hunkSplitter.allocatedLines(
                visibleAdditions.get(i),
                totalVisibleAdditions,
                sourceAdditions,
                allocatedAdditions,
                last
            );
            int deletions = hunkSplitter.allocatedLines(
                visibleDeletions.get(i),
                totalVisibleDeletions,
                sourceDeletions,
                allocatedDeletions,
                last
            );
            allocatedAdditions += additions;
            allocatedDeletions += deletions;
            allocations.add(new DiffHunkLineAllocation(additions, deletions));
        }
        return allocations;
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }
}
