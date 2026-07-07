package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

class DiffChunkingDependencyBoundaryTest {

    private final DiffRiskClassifier riskClassifier = new DiffRiskClassifier();
    private final ChunkBudgetPolicy budgetPolicy = new ChunkBudgetPolicy(riskClassifier);
    private final SemanticDiffSegmenter segmenter = DiffChunkingTestFixtures.segmenter();
    private final SemanticDiffChunkPlanner chunkPlanner = new SemanticDiffChunkPlanner(budgetPolicy);
    private final RiskFilePrioritizer riskFilePrioritizer = new RiskFilePrioritizer(riskClassifier);
    private final PullRequestDiffChunkFactory chunkFactory = new PullRequestDiffChunkFactory(
        new DiffChunkReasonBuilder(riskClassifier)
    );
    private final SemanticDiffScopeResolver scopeResolver = new SemanticDiffScopeResolver(
        new SemanticDiffPathClassifier(),
        new SemanticDiffScopeExtractor()
    );
    private final DiffHunkSplitter hunkSplitter = new DiffHunkSplitter();
    private final DiffHunkLineAllocator lineAllocator = new DiffHunkLineAllocator(hunkSplitter);

    @Test
    void pullRequestDiffChunkerRejectsMissingDependencies() {
        assertMissing("segmenter", () -> chunker(null, chunkPlanner, riskFilePrioritizer, budgetPolicy, chunkFactory));
        assertMissing("chunkPlanner", () -> chunker(segmenter, null, riskFilePrioritizer, budgetPolicy, chunkFactory));
        assertMissing("riskFilePrioritizer", () -> chunker(segmenter, chunkPlanner, null, budgetPolicy, chunkFactory));
        assertMissing("budgetPolicy", () -> chunker(segmenter, chunkPlanner, riskFilePrioritizer, null, chunkFactory));
        assertMissing("chunkFactory", () -> chunker(segmenter, chunkPlanner, riskFilePrioritizer, budgetPolicy, null));
    }

    @Test
    void semanticDiffSegmenterRejectsMissingDependencies() {
        assertMissing("riskClassifier", () -> new SemanticDiffSegmenter(null, scopeResolver, hunkSplitter, lineAllocator));
        assertMissing("scopeResolver", () -> new SemanticDiffSegmenter(riskClassifier, null, hunkSplitter, lineAllocator));
        assertMissing("hunkSplitter", () -> new SemanticDiffSegmenter(riskClassifier, scopeResolver, null, lineAllocator));
        assertMissing("lineAllocator", () -> new SemanticDiffSegmenter(riskClassifier, scopeResolver, hunkSplitter, null));
    }

    @Test
    void diffChunkingSubcomponentsRejectMissingDependencies() {
        assertMissing("riskClassifier", () -> new ChunkBudgetPolicy(null));
        assertMissing("riskClassifier", () -> new DiffChunkReasonBuilder(null));
        assertMissing("reasonBuilder", () -> new PullRequestDiffChunkFactory(null));
        assertMissing("budgetPolicy", () -> new SemanticDiffChunkPlanner(null));
        assertMissing("riskClassifier", () -> new RiskFilePrioritizer(null));
        assertMissing("hunkSplitter", () -> new DiffHunkLineAllocator(null));
        assertMissing("pathClassifier", () -> new SemanticDiffScopeResolver(null, new SemanticDiffScopeExtractor()));
        assertMissing("scopeExtractor", () -> new SemanticDiffScopeResolver(new SemanticDiffPathClassifier(), null));
    }

    private PullRequestDiffChunker chunker(
        SemanticDiffSegmenter segmenter,
        SemanticDiffChunkPlanner chunkPlanner,
        RiskFilePrioritizer riskFilePrioritizer,
        ChunkBudgetPolicy budgetPolicy,
        PullRequestDiffChunkFactory chunkFactory
    ) {
        return new PullRequestDiffChunker(
            segmenter,
            chunkPlanner,
            riskFilePrioritizer,
            budgetPolicy,
            chunkFactory
        );
    }

    private void assertMissing(String dependencyName, ThrowingCallable callable) {
        assertThatThrownBy(callable)
            .isInstanceOf(NullPointerException.class)
            .hasMessage(dependencyName);
    }
}
