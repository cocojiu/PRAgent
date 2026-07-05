package com.repoguard.agent.review;

import com.repoguard.agent.config.ReviewPolicySettings;
import com.repoguard.agent.entity.ReviewPolicyConfig;
import com.repoguard.agent.github.GithubChangedFile;
import com.repoguard.agent.github.GithubPullRequestDiff;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class PullRequestDiffChunker {

    private final DiffRiskClassifier riskClassifier;
    private final SemanticDiffSegmenter segmenter;
    private final SemanticDiffChunkPlanner chunkPlanner;

    public PullRequestDiffChunker() {
        this(new DiffRiskClassifier());
    }

    PullRequestDiffChunker(DiffRiskClassifier riskClassifier) {
        this(riskClassifier, null);
    }

    PullRequestDiffChunker(DiffRiskClassifier riskClassifier, SemanticDiffSegmenter segmenter) {
        this.riskClassifier = riskClassifier == null ? new DiffRiskClassifier() : riskClassifier;
        this.segmenter = segmenter == null ? new SemanticDiffSegmenter(this.riskClassifier) : segmenter;
        this.chunkPlanner = new SemanticDiffChunkPlanner();
    }

    public List<PullRequestDiffChunk> chunk(GithubPullRequestDiff diff) {
        return chunk(diff, DiffChunkingPolicy.defaults());
    }

    public List<PullRequestDiffChunk> chunk(GithubPullRequestDiff diff, ReviewPolicyConfig config) {
        return chunk(diff, DiffChunkingPolicy.from(config));
    }

    public List<PullRequestDiffChunk> chunk(GithubPullRequestDiff diff, ReviewPolicySettings settings) {
        return chunk(diff, DiffChunkingPolicy.from(settings));
    }

    private List<PullRequestDiffChunk> chunk(GithubPullRequestDiff diff, DiffChunkingPolicy policy) {
        List<GithubChangedFile> files = diff.files() == null ? List.of() : diff.files();
        if (!requiresChunking(files, policy)) {
            return List.of(toChunk(diff, files, 1, 1));
        }

        List<List<SemanticDiffSegment>> groupedSegments = chunkPlanner.groupSegments(prioritizedSegments(files), policy);

        List<PullRequestDiffChunk> chunks = new ArrayList<>();
        for (int i = 0; i < groupedSegments.size(); i++) {
            chunks.add(toSemanticChunk(diff, groupedSegments.get(i), i + 1, groupedSegments.size(), policy));
        }
        return chunks;
    }

    private boolean requiresChunking(List<GithubChangedFile> files, DiffChunkingPolicy policy) {
        int totalLines = files.stream().mapToInt(this::changedLines).sum();
        return files.size() > policy.largePrFileThreshold()
            || totalLines > policy.largePrLineThreshold()
            || (files.stream().anyMatch(file -> !riskClassifier.reasons(file).isEmpty()) && files.size() > 1);
    }

    private List<GithubChangedFile> prioritized(List<GithubChangedFile> files) {
        return files.stream()
            .sorted(Comparator
                .comparingInt(riskClassifier::priority)
                .thenComparing(GithubChangedFile::filename, Comparator.nullsLast(String::compareTo)))
            .toList();
    }

    private List<SemanticDiffSegment> prioritizedSegments(List<GithubChangedFile> files) {
        return prioritized(files).stream()
            .flatMap(file -> segmenter.segments(file).stream())
            .sorted(Comparator
                .comparingInt(SemanticDiffSegment::riskPriority)
                .thenComparing(SemanticDiffSegment::chunkGroupKey)
                .thenComparing(SemanticDiffSegment::semanticKey)
                .thenComparing(segment -> segment.file().filename(), Comparator.nullsLast(String::compareTo)))
            .toList();
    }

    private PullRequestDiffChunk toChunk(GithubPullRequestDiff source, List<GithubChangedFile> files, int index, int total) {
        return toChunk(source, files, index, total, DiffChunkingPolicy.defaults());
    }

    private PullRequestDiffChunk toSemanticChunk(
        GithubPullRequestDiff source,
        List<SemanticDiffSegment> segments,
        int index,
        int total,
        DiffChunkingPolicy policy
    ) {
        List<GithubChangedFile> files = segments.stream().map(SemanticDiffSegment::file).toList();
        int additions = segments.stream().mapToInt(SemanticDiffSegment::additions).sum();
        int deletions = segments.stream().mapToInt(SemanticDiffSegment::deletions).sum();
        return new PullRequestDiffChunk(
            index,
            total,
            new GithubPullRequestDiff(source.owner(), source.repository(), source.prNumber(), files),
            distinctFileCount(segments),
            additions,
            deletions,
            semanticChunkReasons(source.files(), segments, policy)
        );
    }

    private PullRequestDiffChunk toChunk(
        GithubPullRequestDiff source,
        List<GithubChangedFile> files,
        int index,
        int total,
        DiffChunkingPolicy policy
    ) {
        int additions = files.stream().mapToInt(file -> safeInt(file.additions())).sum();
        int deletions = files.stream().mapToInt(file -> safeInt(file.deletions())).sum();
        return new PullRequestDiffChunk(
            index,
            total,
            new GithubPullRequestDiff(source.owner(), source.repository(), source.prNumber(), files),
            files.size(),
            additions,
            deletions,
            chunkReasons(files, policy)
        );
    }

    private List<String> chunkReasons(List<GithubChangedFile> files, DiffChunkingPolicy policy) {
        List<String> reasons = new ArrayList<>();
        int changedLines = files.stream().mapToInt(this::changedLines).sum();
        if (files.size() > 1) {
            reasons.add("multi_file");
        }
        if (changedLines > policy.maxLinesPerChunk() / 2) {
            reasons.add("large_churn");
        }
        files.stream()
            .flatMap(file -> riskClassifier.reasons(file).stream())
            .distinct()
            .forEach(reasons::add);
        return reasons.isEmpty() ? List.of("standard") : reasons;
    }

    private List<String> semanticChunkReasons(
        List<GithubChangedFile> sourceFiles,
        List<SemanticDiffSegment> segments,
        DiffChunkingPolicy policy
    ) {
        List<String> reasons = new ArrayList<>();
        int changedLines = segments.stream().mapToInt(SemanticDiffSegment::changedLines).sum();
        if (distinctSourceFileCount(sourceFiles) > 1 || distinctFileCount(segments) > 1) {
            reasons.add("multi_file");
        }
        if (segments.stream().map(SemanticDiffSegment::chunkGroupKey).distinct().count() == 1) {
            reasons.add("semantic_scope");
        }
        if (segments.size() > distinctFileCount(segments)) {
            reasons.add("split_file_scope");
        }
        if (changedLines > policy.maxLinesPerChunk() / 2) {
            reasons.add("large_churn");
        }
        segments.stream()
            .map(SemanticDiffSegment::semanticReason)
            .distinct()
            .forEach(reasons::add);
        segments.stream()
            .flatMap(segment -> riskClassifier.reasons(segment.file()).stream())
            .distinct()
            .forEach(reasons::add);
        return reasons.isEmpty() ? List.of("standard") : reasons;
    }

    private int distinctFileCount(List<SemanticDiffSegment> segments) {
        Set<String> filenames = new LinkedHashSet<>();
        for (SemanticDiffSegment segment : segments) {
            filenames.add(segment.file().filename());
        }
        return filenames.size();
    }

    private int distinctSourceFileCount(List<GithubChangedFile> files) {
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
