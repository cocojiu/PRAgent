package com.repoguard.agent.review;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
class RiskFilePrioritizer {

    private final DiffRiskClassifier riskClassifier;

    RiskFilePrioritizer(DiffRiskClassifier riskClassifier) {
        this.riskClassifier = Objects.requireNonNull(riskClassifier, "riskClassifier");
    }

    List<PullRequestChangedFile> prioritizeFiles(List<PullRequestChangedFile> files) {
        if (files == null || files.isEmpty()) {
            return List.of();
        }
        return files.stream()
            .sorted(Comparator
                .comparingInt(riskClassifier::priority)
                .thenComparing(PullRequestChangedFile::filename, Comparator.nullsLast(String::compareTo)))
            .toList();
    }

    List<SemanticDiffSegment> prioritizeSegments(List<PullRequestChangedFile> files, SemanticDiffSegmenter segmenter) {
        if (files == null || files.isEmpty()) {
            return List.of();
        }
        List<SemanticDiffSegment> segments = prioritizeFiles(files).stream()
            .flatMap(file -> Objects.requireNonNull(segmenter, "segmenter").segments(file).stream())
            .toList();
        return prioritizeSegments(segments);
    }

    List<SemanticDiffSegment> prioritizeSegments(List<SemanticDiffSegment> segments) {
        if (segments == null || segments.isEmpty()) {
            return List.of();
        }
        return segments.stream()
            .sorted(Comparator
                .comparingInt(SemanticDiffSegment::riskPriority)
                .thenComparing(SemanticDiffSegment::chunkGroupKey)
                .thenComparing(SemanticDiffSegment::semanticKey)
                .thenComparing(segment -> segment.file().filename(), Comparator.nullsLast(String::compareTo)))
            .toList();
    }
}
