package com.repoguard.agent.review;

import com.repoguard.agent.common.SensitiveTextSanitizer;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
class ChunkBudgetPolicy {

    private final DiffRiskClassifier riskClassifier;

    ChunkBudgetPolicy(DiffRiskClassifier riskClassifier) {
        this.riskClassifier = Objects.requireNonNull(riskClassifier, "riskClassifier");
    }

    boolean requiresChunking(List<PullRequestChangedFile> files, DiffChunkingPolicy policy) {
        List<PullRequestChangedFile> safeFiles = files == null ? List.of() : files;
        DiffChunkingPolicy effectivePolicy = policy == null ? DiffChunkingPolicy.defaults() : policy;
        long totalLines = safeFiles.stream().mapToLong(this::changedLines).sum();
        return safeFiles.size() > effectivePolicy.largePrFileThreshold()
            || totalLines > effectivePolicy.largePrLineThreshold()
            || (safeFiles.stream().anyMatch(file -> !riskClassifier.reasons(file).isEmpty()) && safeFiles.size() > 1);
    }

    boolean fitsCompactWorkflowChunk(PullRequestDiff diff, DiffChunkingPolicy policy) {
        var files = diff.files();
        return !diff.truncated()
            && files.size() <= Math.min(policy.maxFilesPerChunk(), policy.largePrFileThreshold())
            && files.stream().mapToLong(this::changedLines).sum() <= Math.min(policy.maxLinesPerChunk(), policy.largePrLineThreshold())
            && files.stream().allMatch(file -> file.filename() != null && file.filename().matches("\\.github/workflows/[^/]+\\.ya?ml")
                && file.patch() != null && !file.patch().isBlank())
            && files.stream().mapToLong(file -> 7L + SensitiveTextSanitizer.sanitize(file.filename()).length()
                + SensitiveTextSanitizer.sanitizePreservingWhitespace(file.patch()).length()).sum()
                <= DiffChunkingPolicy.REVIEW_PATCH_FILE_CHARS;
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
        return (int) segments.stream().map(segment -> segment.file().filename()).distinct().count();
    }

    private long changedLines(PullRequestChangedFile file) {
        return (long) Objects.requireNonNullElse(file.additions(), 0) + Objects.requireNonNullElse(file.deletions(), 0);
    }
}
