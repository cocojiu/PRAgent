package com.repoguard.agent.dto;

import java.util.List;

/**
 * PR-level review conclusion assembled from findings, risk profile, and review metadata.
 */
public record PrReviewSummaryDto(
    String overallRisk,
    String summary,
    String mergeRecommendation,
    Boolean recommendMerge,
    Boolean humanReviewRequired,
    List<String> keyRisks,
    List<String> focusFiles,
    String githubCommentBody
) {
}
