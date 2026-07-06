package com.repoguard.agent.review;

import com.repoguard.agent.config.ReviewPolicySettings;
import com.repoguard.agent.entity.ReviewPolicyConfig;
import com.repoguard.agent.github.GithubChangedFile;
import com.repoguard.agent.github.GithubPullRequestDiff;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class PullRequestDiffChunker {

    private final DiffRiskClassifier riskClassifier;
    private final SemanticDiffSegmenter segmenter;
    private final SemanticDiffChunkPlanner chunkPlanner;
    private final RiskFilePrioritizer riskFilePrioritizer;
    private final DiffChunkReasonBuilder reasonBuilder;
    private final ChunkBudgetPolicy budgetPolicy;

    public PullRequestDiffChunker() {
        this(new DiffRiskClassifier());
    }

    PullRequestDiffChunker(DiffRiskClassifier riskClassifier) {
        this(riskClassifier, null);
    }

    PullRequestDiffChunker(DiffRiskClassifier riskClassifier, SemanticDiffSegmenter segmenter) {
        this.riskClassifier = riskClassifier == null ? new DiffRiskClassifier() : riskClassifier;
        this.segmenter = segmenter == null ? new SemanticDiffSegmenter(this.riskClassifier) : segmenter;
        this.budgetPolicy = new ChunkBudgetPolicy(this.riskClassifier);
        this.chunkPlanner = new SemanticDiffChunkPlanner(this.budgetPolicy);
        this.riskFilePrioritizer = new RiskFilePrioritizer(this.riskClassifier);
        this.reasonBuilder = new DiffChunkReasonBuilder(this.riskClassifier);
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
        if (!budgetPolicy.requiresChunking(files, policy)) {
            return List.of(toChunk(diff, files, 1, 1));
        }

        List<List<SemanticDiffSegment>> groupedSegments = chunkPlanner.groupSegments(
            riskFilePrioritizer.prioritizeSegments(files, segmenter),
            policy
        );

        List<PullRequestDiffChunk> chunks = new ArrayList<>();
        for (int i = 0; i < groupedSegments.size(); i++) {
            chunks.add(toSemanticChunk(diff, groupedSegments.get(i), i + 1, groupedSegments.size(), policy));
        }
        return chunks;
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
            reasonBuilder.semanticReasons(source.files(), segments, policy)
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
            reasonBuilder.fileReasons(files, policy)
        );
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
