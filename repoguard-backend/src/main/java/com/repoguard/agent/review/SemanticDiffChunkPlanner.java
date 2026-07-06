package com.repoguard.agent.review;

import com.repoguard.agent.github.GithubChangedFile;
import java.util.ArrayList;
import java.util.List;

class SemanticDiffChunkPlanner {

    private final ChunkBudgetPolicy budgetPolicy;

    SemanticDiffChunkPlanner() {
        this(new ChunkBudgetPolicy(null));
    }

    SemanticDiffChunkPlanner(ChunkBudgetPolicy budgetPolicy) {
        this.budgetPolicy = budgetPolicy == null ? new ChunkBudgetPolicy(null) : budgetPolicy;
    }

    List<List<SemanticDiffSegment>> groupSegments(
        List<SemanticDiffSegment> prioritizedSegments,
        DiffChunkingPolicy policy
    ) {
        List<List<SemanticDiffSegment>> groupedSegments = new ArrayList<>();
        List<SemanticDiffSegment> current = new ArrayList<>();
        int currentLines = 0;
        String currentGroupKey = null;
        for (SemanticDiffSegment segment : prioritizedSegments) {
            int segmentLines = segment.changedLines();
            boolean semanticBoundary = currentGroupKey != null
                && (!currentGroupKey.equals(segment.chunkGroupKey()) || splitsSameFileScope(current, segment));
            boolean currentFull = budgetPolicy.exceedsChunkBudget(current, currentLines, segment, policy);
            if (currentFull || (semanticBoundary && currentLines > 0)) {
                groupedSegments.add(current);
                current = new ArrayList<>();
                currentLines = 0;
                currentGroupKey = null;
            }
            current.add(segment);
            currentLines += segmentLines;
            currentGroupKey = segment.chunkGroupKey();
        }
        if (!current.isEmpty()) {
            groupedSegments.add(current);
        }
        return groupedSegments;
    }

    private boolean splitsSameFileScope(List<SemanticDiffSegment> current, SemanticDiffSegment next) {
        return current.stream().anyMatch(segment -> sameFile(segment.file(), next.file())
            && !segment.semanticKey().equals(next.semanticKey()));
    }

    private boolean sameFile(GithubChangedFile left, GithubChangedFile right) {
        String leftPath = left.filename() == null ? "" : left.filename();
        String rightPath = right.filename() == null ? "" : right.filename();
        return leftPath.equals(rightPath);
    }
}
