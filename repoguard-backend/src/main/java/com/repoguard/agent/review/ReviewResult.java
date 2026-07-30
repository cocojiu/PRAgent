package com.repoguard.agent.review;

import java.math.BigDecimal;
import java.util.List;

public record ReviewResult(
    String riskLevel,
    String llmStatus,
    String statusDetail,
    List<ReviewFindingResult> findings,
    String llmProvider,
    String llmModel,
    Integer llmDurationMs,
    String llmParseStatus,
    String llmPromptSummary,
    Integer llmPromptTokens,
    Integer llmCompletionTokens,
    Integer llmTotalTokens,
    BigDecimal llmEstimatedCost
) {
    public ReviewResult(
        String riskLevel,
        String llmStatus,
        String statusDetail,
        List<ReviewFindingResult> findings
    ) {
        this(riskLevel, llmStatus, statusDetail, findings, null, null, null, null, null, null, null, null, null);
    }

    public static ReviewResult completed(String riskLevel, List<ReviewFindingResult> findings) {
        return new ReviewResult(riskLevel, LlmStatus.COMPLETED.code(), null, findings);
    }

    public static ReviewResult completed(
        String riskLevel,
        List<ReviewFindingResult> findings,
        String llmProvider,
        String llmModel,
        Integer llmDurationMs,
        String llmParseStatus,
        String llmPromptSummary
    ) {
        return completed(riskLevel, findings, llmProvider, llmModel, llmDurationMs, llmParseStatus, llmPromptSummary, null, null, null, null);
    }

    public static ReviewResult completed(
        String riskLevel,
        List<ReviewFindingResult> findings,
        String llmProvider,
        String llmModel,
        Integer llmDurationMs,
        String llmParseStatus,
        String llmPromptSummary,
        Integer llmPromptTokens,
        Integer llmCompletionTokens,
        Integer llmTotalTokens,
        BigDecimal llmEstimatedCost
    ) {
        return new ReviewResult(
            riskLevel,
            LlmStatus.COMPLETED.code(),
            null,
            findings,
            llmProvider,
            llmModel,
            llmDurationMs,
            normalizeLlmParseStatus(llmParseStatus),
            llmPromptSummary,
            llmPromptTokens,
            llmCompletionTokens,
            llmTotalTokens,
            llmEstimatedCost
        );
    }

    public static ReviewResult fallback(String riskLevel, String statusDetail, List<ReviewFindingResult> findings) {
        return new ReviewResult(riskLevel, LlmStatus.FALLBACK.code(), statusDetail, findings);
    }

    public static ReviewResult fallback(
        String riskLevel,
        String statusDetail,
        List<ReviewFindingResult> findings,
        String llmProvider,
        String llmModel,
        Integer llmDurationMs,
        String llmPromptSummary
    ) {
        return new ReviewResult(
            riskLevel,
            LlmStatus.FALLBACK.code(),
            statusDetail,
            findings,
            llmProvider,
            llmModel,
            llmDurationMs,
            LlmParseStatus.FALLBACK.code(),
            llmPromptSummary,
            null,
            null,
            null,
            null
        );
    }

    public ReviewResult withIncompleteInput(String reason, String promptMarker) {
        String adjustedParseStatus = LlmStatus.from(llmStatus) == LlmStatus.FALLBACK
            ? LlmParseStatus.FALLBACK.code()
            : LlmParseStatus.PARTIAL_FALLBACK.code();
        return new ReviewResult(
            riskLevel,
            llmStatus,
            appendLimited(statusDetail, reason, 512),
            findings,
            llmProvider,
            llmModel,
            llmDurationMs,
            adjustedParseStatus,
            appendLimited(llmPromptSummary, promptMarker, 1024),
            llmPromptTokens,
            llmCompletionTokens,
            llmTotalTokens,
            llmEstimatedCost
        );
    }

    private static String normalizeLlmParseStatus(String llmParseStatus) {
        if (llmParseStatus == null || llmParseStatus.isBlank()) {
            return null;
        }
        return LlmParseStatus.from(llmParseStatus).code();
    }

    private static String appendLimited(String current, String additional, int maxLength) {
        String left = current == null || current.isBlank() ? null : current.trim();
        String right = additional == null || additional.isBlank() ? null : additional.trim();
        String combined;
        if (left == null) {
            combined = right;
        } else if (right == null) {
            combined = left;
        } else {
            combined = left + "; " + right;
        }
        if (combined == null || combined.length() <= maxLength) {
            return combined;
        }
        return combined.substring(0, maxLength);
    }

}
