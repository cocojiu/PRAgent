package com.repoguard.agent.review;

final class DiffChunkingTestFixtures {

    private DiffChunkingTestFixtures() {
    }

    static PullRequestDiffChunker chunker() {
        DiffRiskClassifier riskClassifier = new DiffRiskClassifier();
        ChunkBudgetPolicy budgetPolicy = new ChunkBudgetPolicy(riskClassifier);
        return new PullRequestDiffChunker(
            riskClassifier,
            segmenter(riskClassifier),
            new SemanticDiffChunkPlanner(budgetPolicy),
            new RiskFilePrioritizer(riskClassifier),
            budgetPolicy,
            new PullRequestDiffChunkFactory(new DiffChunkReasonBuilder(riskClassifier))
        );
    }

    static SemanticDiffSegmenter segmenter() {
        return segmenter(new DiffRiskClassifier());
    }

    private static SemanticDiffSegmenter segmenter(DiffRiskClassifier riskClassifier) {
        DiffHunkSplitter hunkSplitter = new DiffHunkSplitter();
        return new SemanticDiffSegmenter(
            riskClassifier,
            new SemanticDiffScopeResolver(new SemanticDiffPathClassifier(), new SemanticDiffScopeExtractor()),
            hunkSplitter,
            new DiffHunkLineAllocator(hunkSplitter)
        );
    }
}
