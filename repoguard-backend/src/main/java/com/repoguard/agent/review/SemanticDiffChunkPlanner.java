package com.repoguard.agent.review;

import com.repoguard.agent.github.GithubChangedFile;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

class SemanticDiffChunkPlanner {

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
            boolean currentFull = !current.isEmpty()
                && (distinctFileCount(current) >= policy.maxFilesPerChunk()
                    || currentLines + segmentLines > policy.maxLinesPerChunk());
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

    private int distinctFileCount(List<SemanticDiffSegment> segments) {
        Set<String> filenames = new LinkedHashSet<>();
        for (SemanticDiffSegment segment : segments) {
            filenames.add(segment.file().filename());
        }
        return filenames.size();
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
