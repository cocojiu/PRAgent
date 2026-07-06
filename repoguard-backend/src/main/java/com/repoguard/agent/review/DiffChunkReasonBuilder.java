package com.repoguard.agent.review;

import com.repoguard.agent.github.GithubChangedFile;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

class DiffChunkReasonBuilder {

    private final DiffRiskClassifier riskClassifier;

    DiffChunkReasonBuilder(DiffRiskClassifier riskClassifier) {
        this.riskClassifier = riskClassifier == null ? new DiffRiskClassifier() : riskClassifier;
    }

    List<String> fileReasons(List<GithubChangedFile> files, DiffChunkingPolicy policy) {
        List<GithubChangedFile> safeFiles = files == null ? List.of() : files;
        DiffChunkingPolicy effectivePolicy = policy == null ? DiffChunkingPolicy.defaults() : policy;
        List<String> reasons = new ArrayList<>();
        int changedLines = safeFiles.stream().mapToInt(this::changedLines).sum();
        if (safeFiles.size() > 1) {
            reasons.add("multi_file");
        }
        if (changedLines > effectivePolicy.maxLinesPerChunk() / 2) {
            reasons.add("large_churn");
        }
        safeFiles.stream()
            .flatMap(file -> riskClassifier.reasons(file).stream())
            .distinct()
            .forEach(reasons::add);
        return reasons.isEmpty() ? List.of("standard") : reasons;
    }

    List<String> semanticReasons(
        List<GithubChangedFile> sourceFiles,
        List<SemanticDiffSegment> segments,
        DiffChunkingPolicy policy
    ) {
        List<SemanticDiffSegment> safeSegments = segments == null ? List.of() : segments;
        DiffChunkingPolicy effectivePolicy = policy == null ? DiffChunkingPolicy.defaults() : policy;
        List<String> reasons = new ArrayList<>();
        int changedLines = safeSegments.stream().mapToInt(SemanticDiffSegment::changedLines).sum();
        if (distinctFileCount(sourceFiles) > 1 || distinctSegmentFileCount(safeSegments) > 1) {
            reasons.add("multi_file");
        }
        if (safeSegments.stream().map(SemanticDiffSegment::chunkGroupKey).distinct().count() == 1) {
            reasons.add("semantic_scope");
        }
        if (safeSegments.size() > distinctSegmentFileCount(safeSegments)) {
            reasons.add("split_file_scope");
        }
        if (changedLines > effectivePolicy.maxLinesPerChunk() / 2) {
            reasons.add("large_churn");
        }
        safeSegments.stream()
            .map(SemanticDiffSegment::semanticReason)
            .distinct()
            .forEach(reasons::add);
        safeSegments.stream()
            .flatMap(segment -> riskClassifier.reasons(segment.file()).stream())
            .distinct()
            .forEach(reasons::add);
        return reasons.isEmpty() ? List.of("standard") : reasons;
    }

    private int distinctSegmentFileCount(List<SemanticDiffSegment> segments) {
        Set<String> filenames = new LinkedHashSet<>();
        for (SemanticDiffSegment segment : segments) {
            filenames.add(segment.file().filename());
        }
        return filenames.size();
    }

    private int distinctFileCount(List<GithubChangedFile> files) {
        if (files == null) {
            return 0;
        }
        Set<String> filenames = new LinkedHashSet<>();
        for (GithubChangedFile file : files) {
            filenames.add(file.filename());
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
