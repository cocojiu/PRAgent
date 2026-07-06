package com.repoguard.agent.review;

import com.repoguard.agent.github.GithubChangedFile;
import com.repoguard.agent.github.GithubPullRequestDiff;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

class PullRequestDiffChunkFactory {

    private final DiffChunkReasonBuilder reasonBuilder;

    PullRequestDiffChunkFactory(DiffChunkReasonBuilder reasonBuilder) {
        this.reasonBuilder = reasonBuilder == null
            ? new DiffChunkReasonBuilder(new DiffRiskClassifier())
            : reasonBuilder;
    }

    PullRequestDiffChunk fileChunk(
        GithubPullRequestDiff source,
        List<GithubChangedFile> files,
        int index,
        int total,
        DiffChunkingPolicy policy
    ) {
        List<GithubChangedFile> safeFiles = files == null ? List.of() : files;
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
        GithubPullRequestDiff source,
        List<SemanticDiffSegment> segments,
        int index,
        int total,
        DiffChunkingPolicy policy
    ) {
        List<SemanticDiffSegment> safeSegments = segments == null ? List.of() : segments;
        List<GithubChangedFile> files = safeSegments.stream().map(SemanticDiffSegment::file).toList();
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

    private GithubPullRequestDiff subDiff(GithubPullRequestDiff source, List<GithubChangedFile> files) {
        return new GithubPullRequestDiff(source.owner(), source.repository(), source.prNumber(), files);
    }

    private int additions(List<GithubChangedFile> files) {
        return files.stream().mapToInt(file -> safeInt(file.additions())).sum();
    }

    private int deletions(List<GithubChangedFile> files) {
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
