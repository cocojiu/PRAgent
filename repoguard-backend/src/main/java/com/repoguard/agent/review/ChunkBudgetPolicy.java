package com.repoguard.agent.review;

import com.repoguard.agent.github.GithubChangedFile;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
class ChunkBudgetPolicy {

    private final DiffRiskClassifier riskClassifier;

    ChunkBudgetPolicy(DiffRiskClassifier riskClassifier) {
        this.riskClassifier = Objects.requireNonNull(riskClassifier, "riskClassifier");
    }

    boolean requiresChunking(List<GithubChangedFile> files, DiffChunkingPolicy policy) {
        List<GithubChangedFile> safeFiles = files == null ? List.of() : files;
        DiffChunkingPolicy effectivePolicy = policy == null ? DiffChunkingPolicy.defaults() : policy;
        int totalLines = safeFiles.stream().mapToInt(this::changedLines).sum();
        return safeFiles.size() > effectivePolicy.largePrFileThreshold()
            || totalLines > effectivePolicy.largePrLineThreshold()
            || (safeFiles.stream().anyMatch(file -> !riskClassifier.reasons(file).isEmpty()) && safeFiles.size() > 1);
    }

    boolean exceedsChunkBudget(
        List<SemanticDiffSegment> currentSegments,
        int currentLines,
        SemanticDiffSegment nextSegment,
        DiffChunkingPolicy policy
    ) {
        List<SemanticDiffSegment> current = currentSegments == null ? List.of() : currentSegments;
        if (current.isEmpty()) {
            return false;
        }
        DiffChunkingPolicy effectivePolicy = policy == null ? DiffChunkingPolicy.defaults() : policy;
        int nextLines = nextSegment == null ? 0 : nextSegment.changedLines();
        return distinctFileCount(current) >= effectivePolicy.maxFilesPerChunk()
            || currentLines + nextLines > effectivePolicy.maxLinesPerChunk();
    }

    private int distinctFileCount(List<SemanticDiffSegment> segments) {
        Set<String> filenames = new LinkedHashSet<>();
        for (SemanticDiffSegment segment : segments) {
            filenames.add(segment.file().filename());
        }
        return filenames.size();
    }

    private int changedLines(GithubChangedFile file) {
        return safeInt(file.additions()) + safeInt(file.deletions());
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }
}
