package com.repoguard.agent.dto;

/** Result of comparing a candidate attempt with its previous successful attempt. */
public record ReviewAttemptComparisonDto(
    Long taskId,
    Long baselineAttemptId,
    Long candidateAttemptId,
    String baselineCommitSha,
    String candidateCommitSha,
    boolean comparable,
    String comparabilityReason,
    ReviewFindingComparisonSummaryDto summary,
    PageResponse<ReviewFindingComparisonDto> findings
) {
}
