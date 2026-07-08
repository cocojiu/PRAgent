package com.repoguard.agent.dto;

import java.util.List;

/**
 * PR review detail page summary response.
 *
 * <p>Large collections are intentionally represented as empty compatibility arrays and loaded by dedicated paged
 * endpoints. Totals and aggregate counts stay on this response for first-screen rendering.
 */
public record ReviewTaskSummary(
    Long id,
    Integer prNumber,
    String title,
    String repository,
    String organization,
    String commit,
    String branch,
    String status,
    String riskLevel,
    Integer mqRetries,
    String llmStatus,
    String source,
    String triggerSource,
    String createdAt,
    String duration,
    String failureCategory,
    String failureReason,
    String failureSuggestion,
    String prUrl,
    List<ReviewFindingDto> findings,
    List<MissingTestDto> missingTests,
    List<ChangedFileDto> changedFiles,
    List<ReviewTimelineItem> timeline,
    PrRiskProfileDto riskProfile,
    PrReviewSummaryDto prSummary,
    LlmStatusDto llm,
    ChunkedReviewDto chunkedReview,
    RabbitMqStatusDto rabbitMq,
    Boolean humanReviewRequired,
    String humanReviewStatus,
    String humanReviewNote,
    String humanReviewBy,
    String humanReviewedAt,
    Long findingTotal,
    Long missingTestTotal,
    Long changedFileTotal,
    FindingSeverityCountsDto findingSeverityCounts,
    Boolean archived,
    Long archiveCleanupBatchId,
    String archiveBackupReference,
    String archivedAt
) {
    public static ReviewTaskSummary fromDetail(ReviewTaskDetail detail) {
        return fromDetail(detail, false, null, null, null);
    }

    public static ReviewTaskSummary fromDetail(
        ReviewTaskDetail detail,
        Boolean archived,
        Long archiveCleanupBatchId,
        String archiveBackupReference,
        String archivedAt
    ) {
        return new ReviewTaskSummary(
            detail.id(),
            detail.prNumber(),
            detail.title(),
            detail.repository(),
            detail.organization(),
            detail.commit(),
            detail.branch(),
            detail.status(),
            detail.riskLevel(),
            detail.mqRetries(),
            detail.llmStatus(),
            detail.source(),
            detail.triggerSource(),
            detail.createdAt(),
            detail.duration(),
            detail.failureCategory(),
            detail.failureReason(),
            detail.failureSuggestion(),
            detail.prUrl(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            detail.riskProfile(),
            detail.prSummary(),
            detail.llm(),
            detail.chunkedReview(),
            detail.rabbitMq(),
            detail.humanReviewRequired(),
            detail.humanReviewStatus(),
            detail.humanReviewNote(),
            detail.humanReviewBy(),
            detail.humanReviewedAt(),
            detail.findingTotal(),
            detail.missingTestTotal(),
            detail.changedFileTotal(),
            detail.findingSeverityCounts(),
            archived,
            archiveCleanupBatchId,
            archiveBackupReference,
            archivedAt
        );
    }
}
