package com.repoguard.agent.review;

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
    String llmPromptSummary
) {
    public ReviewResult(
        String riskLevel,
        String llmStatus,
        String statusDetail,
        List<ReviewFindingResult> findings
    ) {
        this(riskLevel, llmStatus, statusDetail, findings, null, null, null, null, null);
    }

    public static ReviewResult completed(String riskLevel, List<ReviewFindingResult> findings) {
        return new ReviewResult(riskLevel, "COMPLETED", null, findings);
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
        return new ReviewResult(riskLevel, "COMPLETED", null, findings, llmProvider, llmModel, llmDurationMs, llmParseStatus, llmPromptSummary);
    }

    public static ReviewResult fallback(String riskLevel, String statusDetail, List<ReviewFindingResult> findings) {
        return new ReviewResult(riskLevel, "FALLBACK", statusDetail, findings);
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
        return new ReviewResult(riskLevel, "FALLBACK", statusDetail, findings, llmProvider, llmModel, llmDurationMs, "fallback", llmPromptSummary);
    }
}
