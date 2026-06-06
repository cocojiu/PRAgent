package com.repoguard.agent.review;

import java.util.List;

public record ReviewResult(
    String riskLevel,
    String llmStatus,
    String statusDetail,
    List<ReviewFindingResult> findings
) {
    public static ReviewResult completed(String riskLevel, List<ReviewFindingResult> findings) {
        return new ReviewResult(riskLevel, "COMPLETED", null, findings);
    }

    public static ReviewResult fallback(String riskLevel, String statusDetail, List<ReviewFindingResult> findings) {
        return new ReviewResult(riskLevel, "FALLBACK", statusDetail, findings);
    }
}
