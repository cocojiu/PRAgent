package com.repoguard.agent.review;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
class SemanticDiffSegmenter {

    private final DiffRiskClassifier riskClassifier;
    private final SemanticDiffScopeResolver scopeResolver;
    private final DiffHunkSplitter hunkSplitter;
    private final DiffHunkLineAllocator lineAllocator;

    SemanticDiffSegmenter(
        DiffRiskClassifier riskClassifier,
        SemanticDiffScopeResolver scopeResolver,
        DiffHunkSplitter hunkSplitter,
        DiffHunkLineAllocator lineAllocator
    ) {
        this.riskClassifier = Objects.requireNonNull(riskClassifier, "riskClassifier");
        this.scopeResolver = Objects.requireNonNull(scopeResolver, "scopeResolver");
        this.hunkSplitter = Objects.requireNonNull(hunkSplitter, "hunkSplitter");
        this.lineAllocator = Objects.requireNonNull(lineAllocator, "lineAllocator");
    }

    List<SemanticDiffSegment> segments(PullRequestChangedFile file) {
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
        List<DiffHunkLineAllocation> allocations = lineAllocator.allocate(file, hunks);
        for (int i = 0; i < hunks.size(); i++) {
            String hunk = hunks.get(i);
            DiffHunkLineAllocation allocation = allocations.get(i);
            segments.add(toSegment(file, hunk, allocation.additions(), allocation.deletions()));
        }
        return segments;
    }

    private SemanticDiffSegment toSegment(
        PullRequestChangedFile file,
        String patch,
        int additions,
        int deletions
    ) {
        return new SemanticDiffSegment(
            new PullRequestChangedFile(
                file.filename(),
                file.status(),
                additions,
                deletions,
                patch,
                file.context()
            ),
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
