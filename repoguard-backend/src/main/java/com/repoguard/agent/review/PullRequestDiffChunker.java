package com.repoguard.agent.review;

import com.repoguard.agent.review.ReviewPolicySettings;
import com.repoguard.agent.entity.ReviewPolicyConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class PullRequestDiffChunker {

    private final SemanticDiffSegmenter segmenter;
    private final SemanticDiffChunkPlanner chunkPlanner;
    private final RiskFilePrioritizer riskFilePrioritizer;
    private final ChunkBudgetPolicy budgetPolicy;
    private final PullRequestDiffChunkFactory chunkFactory;

    PullRequestDiffChunker(
        SemanticDiffSegmenter segmenter,
        SemanticDiffChunkPlanner chunkPlanner,
        RiskFilePrioritizer riskFilePrioritizer,
        ChunkBudgetPolicy budgetPolicy,
        PullRequestDiffChunkFactory chunkFactory
    ) {
        this.segmenter = Objects.requireNonNull(segmenter, "segmenter");
        this.chunkPlanner = Objects.requireNonNull(chunkPlanner, "chunkPlanner");
        this.riskFilePrioritizer = Objects.requireNonNull(riskFilePrioritizer, "riskFilePrioritizer");
        this.budgetPolicy = Objects.requireNonNull(budgetPolicy, "budgetPolicy");
        this.chunkFactory = Objects.requireNonNull(chunkFactory, "chunkFactory");
    }

    public List<PullRequestDiffChunk> chunk(PullRequestDiff diff) {
        return chunk(diff, DiffChunkingPolicy.defaults());
    }

    public List<PullRequestDiffChunk> chunk(PullRequestDiff diff, ReviewPolicyConfig config) {
        return chunk(diff, DiffChunkingPolicy.from(config));
    }

    public List<PullRequestDiffChunk> chunk(PullRequestDiff diff, ReviewPolicySettings settings) {
        return chunk(diff, DiffChunkingPolicy.from(settings));
    }

    private List<PullRequestDiffChunk> chunk(PullRequestDiff diff, DiffChunkingPolicy policy) {
        List<PullRequestChangedFile> files = diff.files() == null ? List.of() : diff.files();
        if (!budgetPolicy.requiresChunking(files, policy)) {
            return List.of(chunkFactory.fileChunk(diff, files, 1, 1, policy));
        }

        List<List<SemanticDiffSegment>> groupedSegments = chunkPlanner.groupSegments(
            riskFilePrioritizer.prioritizeSegments(files, segmenter),
            policy
        );

        List<PullRequestDiffChunk> chunks = new ArrayList<>();
        for (int i = 0; i < groupedSegments.size(); i++) {
            chunks.add(chunkFactory.semanticChunk(diff, groupedSegments.get(i), i + 1, groupedSegments.size(), policy));
        }
        return chunks;
    }
}
