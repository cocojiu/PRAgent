package com.repoguard.agent.dto;

/** Aggregate counts for a pair of review attempts. */
public record ReviewFindingComparisonSummaryDto(
    long newCount,
    long persistingCount,
    long resolvedCount,
    long regressedCount,
    long unmatchedCount,
    long total
) {
}
