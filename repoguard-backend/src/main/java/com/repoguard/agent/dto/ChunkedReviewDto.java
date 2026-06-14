package com.repoguard.agent.dto;

import java.util.List;

/**
 * Structured summary for large PR chunked LLM review.
 */
public record ChunkedReviewDto(
    Boolean enabled,
    Integer chunkCount,
    String aggregateRisk,
    Integer aggregateFindings,
    List<String> reasons
) {
    public static ChunkedReviewDto disabled() {
        return new ChunkedReviewDto(false, 0, null, 0, List.of());
    }
}
