package com.repoguard.agent.review;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
class PullRequestDiffChunkFactory {

    private final DiffChunkReasonBuilder reasonBuilder;

    PullRequestDiffChunkFactory(DiffChunkReasonBuilder reasonBuilder) {
        this.reasonBuilder = Objects.requireNonNull(reasonBuilder, "reasonBuilder");
    }

    PullRequestDiffChunk fileChunk(
        PullRequestDiff source,
        List<PullRequestChangedFile> files,
        int index,
        int total,
        DiffChunkingPolicy policy
    ) {
        List<PullRequestChangedFile> safeFiles = files == null ? List.of() : files;
        return new PullRequestDiffChunk(
            index,
            total,
            subDiff(source, safeFiles),
            safeFiles.size(),
            additions(safeFiles),
            deletions(safeFiles),
            reasonBuilder.fileReasons(safeFiles, policy)
        );
    }

    PullRequestDiffChunk semanticChunk(
        PullRequestDiff source,
        List<SemanticDiffSegment> segments,
        int index,
        int total,
        DiffChunkingPolicy policy
    ) {
        List<SemanticDiffSegment> safeSegments = segments == null ? List.of() : segments;
        List<PullRequestChangedFile> files = safeSegments.stream().map(SemanticDiffSegment::file).toList();
        return new PullRequestDiffChunk(
            index,
            total,
            subDiff(source, files),
            distinctFileCount(safeSegments),
            safeSegments.stream().mapToInt(SemanticDiffSegment::additions).sum(),
            safeSegments.stream().mapToInt(SemanticDiffSegment::deletions).sum(),
            reasonBuilder.semanticReasons(source.files(), safeSegments, policy)
        );
    }

    private PullRequestDiff subDiff(PullRequestDiff source, List<PullRequestChangedFile> files) {
        return new PullRequestDiff(
            source.owner(),
            source.repository(),
            source.prNumber(),
            source.headSha(),
            files,
            source.truncation()
        );
    }

    private int additions(List<PullRequestChangedFile> files) {
        return files.stream().mapToInt(file -> safeInt(file.additions())).sum();
    }

    private int deletions(List<PullRequestChangedFile> files) {
        return files.stream().mapToInt(file -> safeInt(file.deletions())).sum();
    }

    private int distinctFileCount(List<SemanticDiffSegment> segments) {
        Set<String> filenames = new LinkedHashSet<>();
        for (SemanticDiffSegment segment : segments) {
            filenames.add(segment.file().filename());
        }
        return filenames.size();
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }
}
