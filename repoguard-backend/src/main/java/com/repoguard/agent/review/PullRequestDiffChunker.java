package com.repoguard.agent.review;

import com.repoguard.agent.config.ReviewPolicySettings;
import com.repoguard.agent.entity.ReviewPolicyConfig;
import com.repoguard.agent.github.GithubChangedFile;
import com.repoguard.agent.github.GithubPullRequestDiff;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class PullRequestDiffChunker {

    private final DiffRiskClassifier riskClassifier;
    private final SemanticDiffSegmenter segmenter;
    private final SemanticDiffChunkPlanner chunkPlanner;
    private final RiskFilePrioritizer riskFilePrioritizer;
    private final ChunkBudgetPolicy budgetPolicy;
    private final PullRequestDiffChunkFactory chunkFactory;

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
        this.chunkFactory = new PullRequestDiffChunkFactory(new DiffChunkReasonBuilder(this.riskClassifier));
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
