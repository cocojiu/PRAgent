package com.repoguard.agent.review;

import com.repoguard.agent.github.GithubChangedFile;
import java.util.ArrayList;
import java.util.List;

class SemanticDiffSegmenter {

    private final DiffRiskClassifier riskClassifier;
    private final SemanticDiffScopeResolver scopeResolver;
    private final DiffHunkSplitter hunkSplitter;

    SemanticDiffSegmenter(DiffRiskClassifier riskClassifier) {
        this(riskClassifier, new SemanticDiffScopeResolver(), new DiffHunkSplitter());
    }

    SemanticDiffSegmenter(
        DiffRiskClassifier riskClassifier,
        SemanticDiffScopeResolver scopeResolver,
        DiffHunkSplitter hunkSplitter
    ) {
        this.riskClassifier = riskClassifier == null ? new DiffRiskClassifier() : riskClassifier;
        this.scopeResolver = scopeResolver == null ? new SemanticDiffScopeResolver() : scopeResolver;
        this.hunkSplitter = hunkSplitter == null ? new DiffHunkSplitter() : hunkSplitter;
    }

    List<SemanticDiffSegment> segments(GithubChangedFile file) {
        String patch = file.patch();
        if (patch == null || patch.isBlank() || !patch.contains("@@")) {
            return List.of(toSegment(
                file,
                patch,
                safeInt(file.additions()),
                safeInt(file.deletions())
            ));
        }

        List<String> hunks = hunkSplitter.split(patch);
        if (hunks.size() <= 1) {
            return List.of(toSegment(
                file,
                patch,
                safeInt(file.additions()),
                safeInt(file.deletions())
            ));
        }

        List<SemanticDiffSegment> segments = new ArrayList<>();
        List<Integer> visibleAdditions = hunks.stream().map(hunk -> hunkSplitter.countPatchLines(hunk, '+')).toList();
        List<Integer> visibleDeletions = hunks.stream().map(hunk -> hunkSplitter.countPatchLines(hunk, '-')).toList();
        int totalVisibleAdditions = visibleAdditions.stream().mapToInt(Integer::intValue).sum();
        int totalVisibleDeletions = visibleDeletions.stream().mapToInt(Integer::intValue).sum();
        int allocatedAdditions = 0;
        int allocatedDeletions = 0;
        for (int i = 0; i < hunks.size(); i++) {
            String hunk = hunks.get(i);
            boolean last = i == hunks.size() - 1;
            int additions = hunkSplitter.allocatedLines(
                visibleAdditions.get(i),
                totalVisibleAdditions,
                safeInt(file.additions()),
                allocatedAdditions,
                last
            );
            int deletions = hunkSplitter.allocatedLines(
                visibleDeletions.get(i),
                totalVisibleDeletions,
                safeInt(file.deletions()),
                allocatedDeletions,
                last
            );
            allocatedAdditions += additions;
            allocatedDeletions += deletions;
            segments.add(toSegment(file, hunk, additions, deletions));
        }
        return segments;
    }

    private SemanticDiffSegment toSegment(
        GithubChangedFile file,
        String patch,
        int additions,
        int deletions
    ) {
        return new SemanticDiffSegment(
            new GithubChangedFile(file.filename(), file.status(), additions, deletions, patch),
            scopeResolver.chunkGroupKey(file),
            scopeResolver.semanticKey(file, patch),
            scopeResolver.semanticReason(file, patch),
            riskClassifier.priority(file),
            additions,
            deletions
        );
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }
}
