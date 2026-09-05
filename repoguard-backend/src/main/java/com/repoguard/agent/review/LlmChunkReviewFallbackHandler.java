package com.repoguard.agent.review;

import com.repoguard.agent.observability.RepoGuardMetrics;
import com.repoguard.agent.external.ExternalCallException;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Records one failed or skipped chunk before the pipeline's single full-diff rule pass. */
final class LlmChunkReviewFallbackHandler {

    static final String CHUNK_PARTIAL_FAILURE_CATEGORY = "chunk_partial_failure";

    // Preserve the existing logger category so dashboards and queries do not change.
    private static final Logger LOGGER = LoggerFactory.getLogger(LlmChunkReviewAggregator.class);
    private static final ReviewResult DEFERRED_RULE_REVIEW = ReviewResult.completed("INFO", List.of());

    private final RepoGuardMetrics metrics;

    LlmChunkReviewFallbackHandler(RepoGuardMetrics metrics) {
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
        // RuleMergeStage evaluates the full diff once after chunk aggregation. Running the
        // rule engine here would rescan it once per failed, expired, rejected, or capped chunk
        // and can keep a 2C4G worker CPU-bound long after the LLM budget is exhausted.
        return LlmChunkReviewOutcome.fallback(DEFERRED_RULE_REVIEW, category);
    }

    String category(RuntimeException failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof ExternalCallException external) {
                return external.getCategory();
            }
            String message = current.getMessage();
            if (message != null && message.startsWith("Unable to parse LLM review result")) {
                return "llm_parse_failed";
            }
            current = current.getCause();
        }
        return CHUNK_PARTIAL_FAILURE_CATEGORY;
    }
}
