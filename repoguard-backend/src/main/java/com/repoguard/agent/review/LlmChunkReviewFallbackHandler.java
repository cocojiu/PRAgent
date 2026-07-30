package com.repoguard.agent.review;

import com.repoguard.agent.observability.RepoGuardMetrics;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Records one failed or skipped chunk before degrading it to rule review. */
final class LlmChunkReviewFallbackHandler {

    static final String CHUNK_PARTIAL_FAILURE_CATEGORY = "chunk_partial_failure";

    // Preserve the existing logger category so dashboards and queries do not change.
    private static final Logger LOGGER = LoggerFactory.getLogger(LlmChunkReviewAggregator.class);

    private final RuleBasedPullRequestReviewer ruleBasedReviewer;
    private final RepoGuardMetrics metrics;

    LlmChunkReviewFallbackHandler(
        RuleBasedPullRequestReviewer ruleBasedReviewer,
        RepoGuardMetrics metrics
    ) {
        this.ruleBasedReviewer = Objects.requireNonNull(ruleBasedReviewer, "ruleBasedReviewer");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    LlmChunkReviewOutcome fallback(
        PullRequestDiffChunk chunk,
        String category,
        RuntimeException failure
    ) {
        if (failure == null) {
            LOGGER.warn(
                "LLM chunk review skipped chunkIndex={} chunkTotal={} operation=llm_chunk_review "
                    + "result=fallback reason={}",
                chunk.index(),
                chunk.total(),
                category
            );
        } else {
            LOGGER.warn(
                "LLM chunk review failed chunkIndex={} chunkTotal={} operation=llm_chunk_review "
                    + "result=fallback reason={} exceptionType={}",
                chunk.index(),
                chunk.total(),
                category,
                failure.getClass().getName(),
                failure
            );
        }
        metrics.llmFallback(category);
        return LlmChunkReviewOutcome.fallback(ruleBasedReviewer.review(chunk.diff()));
    }
}
